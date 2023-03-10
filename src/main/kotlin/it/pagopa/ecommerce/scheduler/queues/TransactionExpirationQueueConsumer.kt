package it.pagopa.ecommerce.scheduler.queues

import com.azure.core.util.BinaryData
import com.azure.spring.messaging.AzureHeaders
import com.azure.spring.messaging.checkpoint.Checkpointer
import it.pagopa.ecommerce.commons.documents.v1.*
import it.pagopa.ecommerce.commons.utils.v1.TransactionUtils
import it.pagopa.ecommerce.scheduler.client.PaymentGatewayClient
import it.pagopa.ecommerce.scheduler.repositories.TransactionsEventStoreRepository
import it.pagopa.ecommerce.scheduler.repositories.TransactionsViewRepository
import it.pagopa.ecommerce.scheduler.services.eventretry.RefundRetryService
import java.util.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.integration.annotation.ServiceActivator
import org.springframework.messaging.handler.annotation.Header
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono

/**
 * Event consumer for events related to transaction activation. This consumer's responsibilities are
 * to handle expiration of transactions and subsequent refund for transaction stuck in a
 * pending/transient state.
 */
@Service
class TransactionExpirationQueueConsumer(
  @Autowired private val paymentGatewayClient: PaymentGatewayClient,
  @Autowired private val transactionsEventStoreRepository: TransactionsEventStoreRepository<Any>,
  @Autowired
  private val transactionsExpiredEventStoreRepository:
    TransactionsEventStoreRepository<TransactionExpiredData>,
  @Autowired
  private val transactionsRefundedEventStoreRepository:
    TransactionsEventStoreRepository<TransactionRefundedData>,
  @Autowired private val transactionsViewRepository: TransactionsViewRepository,
  @Autowired private val transactionUtils: TransactionUtils,
  @Autowired private val refundRetryService: RefundRetryService
) {

  var logger: Logger = LoggerFactory.getLogger(TransactionExpirationQueueConsumer::class.java)

  private fun getTransactionIdFromPayload(data: BinaryData): Mono<String> {
    val idFromActivatedEvent =
      data.toObjectAsync(TransactionActivatedEvent::class.java).map { it.transactionId }
    val idFromClosedEvent =
      data.toObjectAsync(TransactionClosedEvent::class.java).map { it.transactionId }

    return Mono.firstWithValue(idFromActivatedEvent, idFromClosedEvent)
  }

  @ServiceActivator(inputChannel = "transactionexpiredchannel", outputChannel = "nullChannel")
  fun messageReceiver(
    @Payload payload: ByteArray,
    @Header(AzureHeaders.CHECKPOINTER) checkpointer: Checkpointer
  ): Mono<Void> {
    val checkpoint = checkpointer.success()

    val transactionId = getTransactionIdFromPayload(BinaryData.fromBytes(payload))
    val baseTransaction = reduceEvents(transactionId, transactionsEventStoreRepository)
    val refundPipeline =
      baseTransaction
        .filter { transactionUtils.isTransientStatus(it.status) }
        .flatMap { tx ->
          val refundable = isTransactionRefundable(tx)
          val isTransactionExpired = isTransactionExpired(tx)
          logger.info(
            "Transaction ${tx.transactionId.value} in status ${tx.status}, refundable: $refundable, expired: $isTransactionExpired")
          if (!isTransactionExpired) {
            updateTransactionToExpired(
              tx, transactionsExpiredEventStoreRepository, transactionsViewRepository, refundable)
          } else {
            Mono.just(tx)
          }
        }
        .filter { isTransactionRefundable(it) }
        .flatMap {
          updateTransactionToRefundRequested(
            it, transactionsRefundedEventStoreRepository, transactionsViewRepository)
        }
        .flatMap { tx ->
          refundTransaction(
            tx,
            transactionsRefundedEventStoreRepository,
            transactionsViewRepository,
            paymentGatewayClient,
            refundRetryService)
        }

    return checkpoint.then(refundPipeline).then()
  }
}