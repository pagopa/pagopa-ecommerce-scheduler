package it.pagopa.ecommerce.scheduler.queues

import com.azure.core.util.BinaryData
import com.azure.spring.messaging.AzureHeaders
import com.azure.spring.messaging.checkpoint.Checkpointer
import it.pagopa.ecommerce.commons.documents.v1.*
import it.pagopa.ecommerce.commons.domain.v1.EmptyTransaction
import it.pagopa.ecommerce.commons.domain.v1.Transaction
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransactionWithRefundRequested
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
 * Event consumer for events related to refund retry. This consumer's responsibilities are to handle
 * refund process retry for a given transaction
 */
@Service
class TransactionRefundRetryQueueConsumer(
  @Autowired private val paymentGatewayClient: PaymentGatewayClient,
  @Autowired private val transactionsEventStoreRepository: TransactionsEventStoreRepository<Any>,
  @Autowired
  private val transactionsRefundedEventStoreRepository:
    TransactionsEventStoreRepository<TransactionRefundedData>,
  @Autowired private val transactionsViewRepository: TransactionsViewRepository,
  @Autowired private val refundRetryService: RefundRetryService
) {

  var logger: Logger = LoggerFactory.getLogger(TransactionRefundRetryQueueConsumer::class.java)

  private fun parseInputEvent(data: BinaryData): Mono<TransactionRefundRetriedEvent> {
    return data.toObjectAsync(TransactionRefundRetriedEvent::class.java)
  }

  @ServiceActivator(inputChannel = "transactionrefundretrychannel", outputChannel = "nullChannel")
  fun messageReceiver(
    @Payload payload: ByteArray,
    @Header(AzureHeaders.CHECKPOINTER) checkpointer: Checkpointer
  ): Mono<Void> {
    val checkpoint = checkpointer.success()
    val event = parseInputEvent(BinaryData.fromBytes(payload))
    val baseTransaction =
      event
        .flatMapMany { transactionsEventStoreRepository.findByTransactionId(it.transactionId) }
        .reduce(EmptyTransaction(), Transaction::applyEvent)
        .cast(BaseTransactionWithRefundRequested::class.java)
    val refundPipeline =
      baseTransaction
        .flatMap { tx ->
          refundTransaction(
            tx,
            transactionsRefundedEventStoreRepository,
            transactionsViewRepository,
            paymentGatewayClient)
        }
        .onErrorResume { exception ->
          event.map {
            logger.error(
              "Transaction requestRefund error for transaction $it : ${exception.message}")
          }
          baseTransaction
            .flatMap { tx ->
              event.flatMap { event ->
                refundRetryService.enqueueRetryEvent(tx, event.data.retryCount)
              }
            }
            .then(baseTransaction)
        }
    return checkpoint.then(refundPipeline).then()
  }
}
