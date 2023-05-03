package it.pagopa.ecommerce.eventdispatcher.services.eventretry

import com.azure.core.util.BinaryData
import com.azure.storage.queue.QueueAsyncClient
import it.pagopa.ecommerce.commons.documents.v1.TransactionEvent
import it.pagopa.ecommerce.commons.documents.v1.TransactionRetriedData
import it.pagopa.ecommerce.commons.domain.v1.TransactionId
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto
import it.pagopa.ecommerce.eventdispatcher.exceptions.NoRetryAttemptsLeftException
import it.pagopa.ecommerce.eventdispatcher.repositories.TransactionsEventStoreRepository
import it.pagopa.ecommerce.eventdispatcher.repositories.TransactionsViewRepository
import java.time.Duration
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono

abstract class RetryEventService<E>(
  private val queueAsyncClient: QueueAsyncClient,
  private val retryOffset: Int,
  private val maxAttempts: Int,
  private val viewRepository: TransactionsViewRepository,
  private val retryEventStoreRepository: TransactionsEventStoreRepository<TransactionRetriedData>,
  private val logger: Logger = LoggerFactory.getLogger(RetryEventService::class.java)
) where E : TransactionEvent<TransactionRetriedData> {

  fun enqueueRetryEvent(baseTransaction: BaseTransaction, retriedCount: Int): Mono<Void> {
    val retryEvent =
      buildRetryEvent(baseTransaction.transactionId, TransactionRetriedData(retriedCount + 1))
    return Mono.just(retryEvent)
      .filter { it.data.retryCount <= maxAttempts }
      .switchIfEmpty(
        Mono.error(
          NoRetryAttemptsLeftException(
            eventCode = retryEvent.eventCode, transactionId = baseTransaction.transactionId)))
      .flatMap { storeEventAndUpdateView(it, newTransactionStatus()) }
      .flatMap {
        enqueueMessage(it, Duration.ofSeconds((retryOffset * it.data.retryCount).toLong()))
      }
      .doOnError {
        logger.error(
          "Error processing retry event for transaction with id: [${retryEvent.transactionId}]", it)
      }
  }

  abstract fun buildRetryEvent(
    transactionId: TransactionId,
    transactionRetriedData: TransactionRetriedData
  ): E

  abstract fun newTransactionStatus(): TransactionStatusDto

  private fun storeEventAndUpdateView(event: E, newStatus: TransactionStatusDto): Mono<E> =
    Mono.just(event)
      .flatMap { retryEventStoreRepository.save(it) }
      .flatMap { viewRepository.findByTransactionId(it.transactionId) }
      .flatMap {
        it.status = newStatus
        viewRepository.save(it).flatMap { Mono.just(event) }
      }

  private fun enqueueMessage(event: E, visibilityTimeout: Duration): Mono<Void> =
    Mono.just(event).flatMap { eventToSend ->
      queueAsyncClient
        .sendMessageWithResponse(
          BinaryData.fromObject(eventToSend),
          visibilityTimeout,
          null, // timeToLive
        )
        .doOnNext {
          logger.info(
            "Event: [$event] successfully sent with visibility timeout: [${it.value.timeNextVisible}] ms to queue: [${queueAsyncClient.queueName}]")
        }
        .then()
        .doOnError { exception -> logger.error("Error sending event: [${event}].", exception) }
    }
}
