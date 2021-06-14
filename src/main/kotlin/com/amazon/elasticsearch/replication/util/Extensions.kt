/*
 *   Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License").
 *   You may not use this file except in compliance with the License.
 *   A copy of the License is located at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   or in the "license" file accompanying this file. This file is distributed
 *   on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *   express or implied. See the License for the specific language governing
 *   permissions and limitations under the License.
 */

package com.amazon.elasticsearch.replication.util

import com.amazon.elasticsearch.replication.metadata.store.ReplicationContext
import com.amazon.elasticsearch.replication.metadata.store.ReplicationMetadata
import com.amazon.opendistroforelasticsearch.commons.authuser.User
import kotlinx.coroutines.delay
import org.apache.logging.log4j.Logger
import org.elasticsearch.ElasticsearchException
import org.elasticsearch.action.ActionListener
import org.elasticsearch.action.ActionRequest
import org.elasticsearch.action.ActionResponse
import org.elasticsearch.action.ActionType
import org.elasticsearch.action.index.IndexRequestBuilder
import org.elasticsearch.action.index.IndexResponse
import org.elasticsearch.client.Client
import org.elasticsearch.common.util.concurrent.ThreadContext
import org.elasticsearch.index.store.Store
import org.elasticsearch.transport.ConnectTransportException
import org.elasticsearch.transport.NodeDisconnectedException

/*
 * Extension function to use the store object
 */
fun Store.performOp(tryBlock: () -> Unit, finalBlock: () -> Unit = {}) {
    incRef()
    try {
        tryBlock()
    }
    finally {
        finalBlock()
        decRef()
    }
}

fun <T: ActionResponse>Client.execute(replMetadata: ReplicationMetadata, actionType: ActionType<T>,
                                      actionRequest: ActionRequest, timeout: Long): T {
    var storedContext: ThreadContext.StoredContext? = null
    try {
        storedContext = threadPool().threadContext.stashContext()
        SecurityContext.setBasedOnActions(replMetadata, actionType.name(), threadPool().threadContext)
        return execute(actionType, actionRequest).actionGet(timeout)
    } finally {
        storedContext?.restore()
    }
}

fun <T>Client.executeBlockUnderSecurityContext(replContext: ReplicationContext, block: () -> T): T {
    var storedContext: ThreadContext.StoredContext? = null
    try {
        storedContext = threadPool().threadContext.stashContext()
        SecurityContext.asRolesInjection(this.threadPool().threadContext, replContext.user?.toInjectedRoles())
        return block()
    } finally {
        storedContext?.restore()
    }
}

fun IndexRequestBuilder.execute(id: String, listener: ActionListener<IndexResponse>) {
    execute(listener)
}
/**
 * Retries a given block of code.
 * Only specified error are retried
 *
 * @param numberOfRetries - Number of retries
 * @param backoff - Retry interval
 * @param maxTimeOut - Time out for retries
 * @param factor - ExponentialBackoff factor
 * @param log - logger used to log intermediate failures
 * @param retryOn - javaClass name of Elasticsearch exceptions that should be retried along with default retryable exceptions
 * @param block - the block of code to retry. This should be a suspend function.
 */
suspend fun <Req: ActionRequest, Resp: ActionResponse> Client.suspendExecuteWithRetries(
        replicationMetadata: ReplicationMetadata,
        action: ActionType<Resp>,
        req: Req,
        numberOfRetries: Int = 5,
        backoff: Long = 10000,        // 10 seconds
        maxTimeOut: Long = 600000,    // 10 minutes
        factor: Double = 2.0,
        log: Logger,
        retryOn: ArrayList<Class<*>> = ArrayList()) : Resp {
    var currentBackoff = backoff
    retryOn.addAll(defaultRetryableExceptions())
    repeat(numberOfRetries - 1) {
        try {
            return suspendExecute(replicationMetadata, action, req)
        } catch (e: ElasticsearchException) {
            if (retryOn.contains(e.javaClass)) {
                log.warn("Encountered a failure. Retrying in ${currentBackoff/1000} seconds.", e)
                delay(currentBackoff)
                currentBackoff = (currentBackoff * factor).toLong().coerceAtMost(maxTimeOut)
            } else {
                throw e
            }
        }
    }
    return suspendExecute(replicationMetadata, action, req) // last attempt
}

private fun defaultRetryableExceptions(): ArrayList<Class<*>> {
    val retryableExceptions = ArrayList<Class<*>>()
    retryableExceptions.add(NodeDisconnectedException::class.java)
    retryableExceptions.add(ConnectTransportException::class.java)
    return retryableExceptions
}

fun User.overrideFgacRole(fgacRole: String?): User? {
    if(fgacRole == null) {
        return null
    }
    return User(this.name, this.backendRoles, listOf(fgacRole),
            this.customAttNames, this.requestedTenant)
}

fun User.toInjectedUser(): String? {
    return "${name}|${backendRoles.joinToString(separator=",")}"
}

fun User.toInjectedRoles(): String? {
    return "${name}|${roles.joinToString(separator=",")}"
}
