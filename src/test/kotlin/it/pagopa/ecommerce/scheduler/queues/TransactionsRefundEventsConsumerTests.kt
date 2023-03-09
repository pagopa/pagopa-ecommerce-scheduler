package it.pagopa.ecommerce.scheduler.queues

import com.azure.core.util.BinaryData
import com.azure.spring.messaging.checkpoint.Checkpointer
import it.pagopa.ecommerce.commons.documents.v1.*
import it.pagopa.ecommerce.commons.domain.v1.TransactionEventCode
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils.transactionAuthorizationRequestedEvent
import it.pagopa.ecommerce.scheduler.client.PaymentGatewayClient
import it.pagopa.ecommerce.scheduler.repositories.TransactionsEventStoreRepository
import it.pagopa.ecommerce.scheduler.repositories.TransactionsViewRepository
import it.pagopa.ecommerce.scheduler.services.eventretry.RefundRetryService
import it.pagopa.generated.ecommerce.gateway.v1.dto.PostePayRefundResponseDto
import java.util.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mockito
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toFlux
import reactor.test.StepVerifier

@ExtendWith(MockitoExtension::class)
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsRefundEventsConsumerTests {
  private val checkpointer: Checkpointer = mock()

  private val transactionsEventStoreRepository: TransactionsEventStoreRepository<Any> = mock()

  private val paymentGatewayClient: PaymentGatewayClient = mock()

  private val refundRetryService: RefundRetryService = mock()

  private val transactionsRefundedEventStoreRepository:
    TransactionsEventStoreRepository<TransactionRefundedData> =
    mock()

  @Captor
  private lateinit var refundEventStoreCaptor:
    ArgumentCaptor<TransactionEvent<TransactionRefundedData>>

  private val transactionsViewRepository: TransactionsViewRepository = mock()

  private val transactionRefundedEventsConsumer =
    TransactionsRefundConsumer(
      paymentGatewayClient,
      transactionsEventStoreRepository,
      transactionsRefundedEventStoreRepository,
      transactionsViewRepository,
      refundRetryService)

  @Test
  fun `consumer processes refund request event correctly with pgs refund`() = runTest {
    val activationEvent = TransactionTestUtils.transactionActivateEvent() as TransactionEvent<Any>
    val authorizationRequestEvent =
      transactionAuthorizationRequestedEvent() as TransactionEvent<Any>
    val authorizationCompleteEvent =
      TransactionTestUtils.transactionAuthorizationCompletedEvent() as TransactionEvent<Any>
    val refundRequestedEvent =
      TransactionRefundRequestedEvent(
        TransactionTestUtils.TRANSACTION_ID,
        TransactionRefundedData(TransactionStatusDto.REFUND_REQUESTED))
        as TransactionEvent<Any>

    val gatewayClientResponse = PostePayRefundResponseDto().apply { refundOutcome = "OK" }

    val events =
      listOf(
        activationEvent,
        authorizationRequestEvent,
        authorizationCompleteEvent,
        refundRequestedEvent)

    // not working
    val expectedRefundedEvent =
      TransactionRefundedEvent(
        TransactionTestUtils.TRANSACTION_ID,
        TransactionRefundedData(TransactionStatusDto.AUTHORIZATION_COMPLETED))

    /* preconditions */
    given(checkpointer.success()).willReturn(Mono.empty())
    given(transactionsEventStoreRepository.findByTransactionId(any())).willReturn(events.toFlux())
    given(transactionsViewRepository.save(any())).willAnswer { Mono.just(it.arguments[0]) }
    given(transactionsRefundedEventStoreRepository.save(refundEventStoreCaptor.capture()))
      .willAnswer { Mono.just(it.arguments[0]) }
    given(paymentGatewayClient.requestRefund(any())).willReturn(Mono.just(gatewayClientResponse))

    val refundedEventId = UUID.fromString(expectedRefundedEvent.id)

    Mockito.mockStatic(UUID::class.java).use { uuid ->
      uuid.`when`<Any>(UUID::randomUUID).thenReturn(refundedEventId)
      uuid.`when`<Any> { UUID.fromString(any()) }.thenCallRealMethod()

      /* test */
      StepVerifier.create(
          transactionRefundedEventsConsumer.messageReceiver(
            BinaryData.fromObject(refundRequestedEvent).toBytes(), checkpointer))
        .expectNext()
        .verifyComplete()

      /* Asserts */
      verify(checkpointer, Mockito.times(1)).success()
      verify(paymentGatewayClient, Mockito.times(1))
        .requestRefund(UUID.fromString(TransactionTestUtils.TRANSACTION_ID))
      verify(transactionsRefundedEventStoreRepository, Mockito.times(1)).save(any())
      verify(refundRetryService, times(0)).enqueueRetryEvent(any(), any())
      val storedEvent = refundEventStoreCaptor.value
      assertEquals(TransactionEventCode.TRANSACTION_REFUNDED_EVENT, storedEvent.eventCode)
      assertEquals(TransactionStatusDto.REFUND_REQUESTED, storedEvent.data.statusBeforeRefunded)
    }
  }

  @Test
  fun `consumer processes refund request event for a transaction without refund requested`() =
    runTest {
      val activationEvent = TransactionTestUtils.transactionActivateEvent() as TransactionEvent<Any>
      val authorizationRequestEvent =
        transactionAuthorizationRequestedEvent() as TransactionEvent<Any>
      val authorizationCompleteEvent =
        TransactionTestUtils.transactionAuthorizationCompletedEvent() as TransactionEvent<Any>
      val refundRequestedEvent =
        TransactionRefundRequestedEvent(
          TransactionTestUtils.TRANSACTION_ID,
          TransactionRefundedData(TransactionStatusDto.REFUND_REQUESTED))
          as TransactionEvent<Any>

      val events =
        listOf(
          activationEvent,
          authorizationRequestEvent,
          authorizationCompleteEvent,
        )

      /* preconditions */
      given(checkpointer.success()).willReturn(Mono.empty())
      given(transactionsEventStoreRepository.findByTransactionId(any())).willReturn(events.toFlux())
      given(transactionsViewRepository.save(any())).willAnswer { Mono.just(it.arguments[0]) }

      /* test */

      StepVerifier.create(
          transactionRefundedEventsConsumer.messageReceiver(
            BinaryData.fromObject(refundRequestedEvent).toBytes(), checkpointer))
        .expectNext()
        .verifyComplete()

      /* Asserts */
      verify(checkpointer, Mockito.times(1)).success()
      verify(paymentGatewayClient, Mockito.times(0))
        .requestRefund(UUID.fromString(TransactionTestUtils.TRANSACTION_ID))
      verify(transactionsRefundedEventStoreRepository, Mockito.times(0)).save(any())
      verify(refundRetryService, times(0)).enqueueRetryEvent(any(), any())
    }

  @Test
  fun `consumer enqueue refund retry event for KO response from PGS`() = runTest {
    val activationEvent = TransactionTestUtils.transactionActivateEvent() as TransactionEvent<Any>
    val authorizationRequestEvent =
      transactionAuthorizationRequestedEvent() as TransactionEvent<Any>
    val authorizationCompleteEvent =
      TransactionTestUtils.transactionAuthorizationCompletedEvent() as TransactionEvent<Any>
    val refundRequestedEvent =
      TransactionRefundRequestedEvent(
        TransactionTestUtils.TRANSACTION_ID,
        TransactionRefundedData(TransactionStatusDto.REFUND_REQUESTED))
        as TransactionEvent<Any>

    val gatewayClientResponse = PostePayRefundResponseDto().apply { refundOutcome = "KO" }

    val events =
      listOf(
        activationEvent,
        authorizationRequestEvent,
        authorizationCompleteEvent,
        refundRequestedEvent)

    // not working
    val expectedRefundedEvent =
      TransactionRefundedEvent(
        TransactionTestUtils.TRANSACTION_ID,
        TransactionRefundedData(TransactionStatusDto.AUTHORIZATION_COMPLETED))

    /* preconditions */
    given(checkpointer.success()).willReturn(Mono.empty())
    given(transactionsEventStoreRepository.findByTransactionId(any())).willReturn(events.toFlux())
    given(transactionsViewRepository.save(any())).willAnswer { Mono.just(it.arguments[0]) }
    given(transactionsRefundedEventStoreRepository.save(refundEventStoreCaptor.capture()))
      .willAnswer { Mono.just(it.arguments[0]) }
    given(paymentGatewayClient.requestRefund(any())).willReturn(Mono.just(gatewayClientResponse))
    given(refundRetryService.enqueueRetryEvent(any(), any())).willReturn(Mono.empty())
    val refundedEventId = UUID.fromString(expectedRefundedEvent.id)

    Mockito.mockStatic(UUID::class.java).use { uuid ->
      uuid.`when`<Any>(UUID::randomUUID).thenReturn(refundedEventId)
      uuid.`when`<Any> { UUID.fromString(any()) }.thenCallRealMethod()

      /* test */

      StepVerifier.create(
          transactionRefundedEventsConsumer.messageReceiver(
            BinaryData.fromObject(refundRequestedEvent).toBytes(), checkpointer))
        .expectNext()
        .verifyComplete()

      /* Asserts */
      verify(checkpointer, Mockito.times(1)).success()
      verify(paymentGatewayClient, Mockito.times(1))
        .requestRefund(UUID.fromString(TransactionTestUtils.TRANSACTION_ID))
      verify(transactionsRefundedEventStoreRepository, Mockito.times(1)).save(any())
      verify(refundRetryService, times(1)).enqueueRetryEvent(any(), any())

      val storedEvent = refundEventStoreCaptor.value
      assertEquals(TransactionEventCode.TRANSACTION_REFUND_ERROR_EVENT, storedEvent.eventCode)
      assertEquals(TransactionStatusDto.REFUND_REQUESTED, storedEvent.data.statusBeforeRefunded)
    }
  }
}