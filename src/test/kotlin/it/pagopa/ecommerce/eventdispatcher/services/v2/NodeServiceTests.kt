package it.pagopa.ecommerce.eventdispatcher.services.v2

import it.pagopa.ecommerce.commons.client.NpgClient
import it.pagopa.ecommerce.commons.documents.v2.Transaction
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationRequestData
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationRequestedEvent
import it.pagopa.ecommerce.commons.documents.v2.TransactionEvent
import it.pagopa.ecommerce.commons.documents.v2.authorization.NpgTransactionGatewayAuthorizationData
import it.pagopa.ecommerce.commons.documents.v2.authorization.NpgTransactionGatewayAuthorizationRequestedData
import it.pagopa.ecommerce.commons.documents.v2.authorization.RedirectTransactionGatewayAuthorizationData
import it.pagopa.ecommerce.commons.documents.v2.authorization.RedirectTransactionGatewayAuthorizationRequestedData
import it.pagopa.ecommerce.commons.domain.Email
import it.pagopa.ecommerce.commons.domain.TransactionId
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.OperationResultDto
import it.pagopa.ecommerce.commons.utils.EuroUtils
import it.pagopa.ecommerce.commons.v2.TransactionTestUtils.*
import it.pagopa.ecommerce.eventdispatcher.client.NodeClient
import it.pagopa.ecommerce.eventdispatcher.exceptions.BadTransactionStatusException
import it.pagopa.ecommerce.eventdispatcher.queues.v2.helpers.ClosePaymentOutcome
import it.pagopa.ecommerce.eventdispatcher.repositories.TransactionsEventStoreRepository
import it.pagopa.ecommerce.eventdispatcher.utils.ConfidentialDataUtils
import it.pagopa.ecommerce.eventdispatcher.utils.PaymentCode
import it.pagopa.generated.ecommerce.nodo.v2.dto.*
import it.pagopa.generated.ecommerce.nodo.v2.dto.CardAdditionalPaymentInformationsDto.OutcomePaymentGatewayEnum
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.stream.Stream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.ArgumentCaptor
import org.mockito.BDDMockito.given
import org.mockito.Captor
import org.mockito.kotlin.any
import org.mockito.kotlin.capture
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.springframework.test.context.junit.jupiter.SpringExtension
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toFlux

@ExtendWith(SpringExtension::class)
@OptIn(ExperimentalCoroutinesApi::class)
class NodeServiceTests {

  private val nodeClient: NodeClient = mock {}

  private val transactionsEventStoreRepository: TransactionsEventStoreRepository<Any> = mock {}

  private val confidentialDataUtils: ConfidentialDataUtils = mock {}

  private val nodeService =
    NodeService(
      nodeClient = nodeClient,
      transactionsEventStoreRepository = transactionsEventStoreRepository,
      confidentialDataUtils = confidentialDataUtils)

  @Captor
  private lateinit var closePaymentRequestCaptor: ArgumentCaptor<CardClosePaymentRequestV2Dto>

  @Captor
  private lateinit var redirectClosePaymentRequestCaptor:
    ArgumentCaptor<RedirectClosePaymentRequestV2Dto>

  @Captor
  private lateinit var paypalClosePaymentRequestCaptor:
    ArgumentCaptor<PayPalClosePaymentRequestV2Dto>

  @Captor
  private lateinit var satispayClosePaymentRequestCaptor:
    ArgumentCaptor<SatispayClosePaymentRequestV2Dto>

  @Captor
  private lateinit var applepayClosePaymentRequestCaptor:
    ArgumentCaptor<ApplePayClosePaymentRequestV2Dto>

  @Captor
  private lateinit var bancomatPayClosePaymentRequestCaptor:
    ArgumentCaptor<BancomatPayClosePaymentRequestV2Dto>

  @Captor
  private lateinit var myBankClosePaymentRequestCaptor:
    ArgumentCaptor<MyBankClosePaymentRequestV2Dto>

  @Test
  fun `closePayment returns successfully for close payment on user cancel request transaction`() =
    runTest {
      val transactionOutcome = ClosePaymentOutcome.KO

      val activatedEvent = transactionActivateEvent()
      val canceledEvent = transactionUserCanceledEvent()
      val events = listOf(activatedEvent, canceledEvent) as List<TransactionEvent<Any>>
      val transactionId = activatedEvent.transactionId
      val amount =
        BigDecimal(activatedEvent.data.paymentNotices.stream().mapToInt { el -> el.amount }.sum())
      val closePaymentResponse =
        ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }

      /* preconditions */
      given(
          transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(
            TRANSACTION_ID))
        .willReturn(events.toFlux())

      given(nodeClient.closePayment(capture(closePaymentRequestCaptor)))
        .willReturn(Mono.just(closePaymentResponse))

      /* test */
      assertEquals(
        closePaymentResponse,
        nodeService.closePayment(TransactionId(transactionId), transactionOutcome))

      assertEquals(transactionId, closePaymentRequestCaptor.value.transactionId)
      assertEquals(
        CardClosePaymentRequestV2Dto.OutcomeEnum.KO, closePaymentRequestCaptor.value.outcome)
      // check additionalPaymentInformations
      assertNull(closePaymentRequestCaptor.value.additionalPaymentInformations)
      // check transactionDetails
      assertEquals(
        UserDto.TypeEnum.GUEST, closePaymentRequestCaptor.value.transactionDetails.user.type)
      assertEquals(
        TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_CANCELED.status,
        closePaymentRequestCaptor.value.transactionDetails.transaction.transactionStatus)
      assertEquals(
        Transaction.ClientId.CHECKOUT.name,
        closePaymentRequestCaptor.value.transactionDetails.info.clientId)
      assertEquals(TIPO_VERSAMENTO_CP, closePaymentRequestCaptor.value.transactionDetails.info.type)
      assertEquals(
        closePaymentRequestCaptor.value.transactionDetails.transaction.amount,
        closePaymentRequestCaptor.value.transactionDetails.transaction.grandTotal)
      assertNull(closePaymentRequestCaptor.value.transactionDetails.transaction.fee)
      assertNotNull(closePaymentRequestCaptor.value.transactionDetails.transaction.amount)
      assertNotNull(closePaymentRequestCaptor.value.transactionDetails.transaction.grandTotal)
      assertEquals(amount, closePaymentRequestCaptor.value.transactionDetails.transaction.amount)
      assertEquals(
        amount, closePaymentRequestCaptor.value.transactionDetails.transaction.grandTotal)
      assertNotNull(closePaymentRequestCaptor.value.transactionDetails.transaction.creationDate)
    }

  @Test
  fun `closePayment returns successfully for retry close payment on user cancel request transaction`() =
    runTest {
      val transactionOutcome = ClosePaymentOutcome.KO

      val activatedEvent = transactionActivateEvent()
      val canceledEvent = transactionUserCanceledEvent()
      val closureError = transactionClosureErrorEvent()

      val events =
        listOf(activatedEvent, canceledEvent, closureError) as List<TransactionEvent<Any>>
      val transactionId = activatedEvent.transactionId
      val amount =
        BigDecimal(activatedEvent.data.paymentNotices.stream().mapToInt { el -> el.amount }.sum())

      val closePaymentResponse =
        ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }

      /* preconditions */
      given(
          transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(
            TRANSACTION_ID))
        .willReturn(events.toFlux())

      given(nodeClient.closePayment(capture(closePaymentRequestCaptor)))
        .willReturn(Mono.just(closePaymentResponse))
      /* test */
      assertEquals(
        closePaymentResponse,
        nodeService.closePayment(TransactionId(transactionId), transactionOutcome))

      assertEquals(transactionId, closePaymentRequestCaptor.value.transactionId)
      assertEquals(
        CardClosePaymentRequestV2Dto.OutcomeEnum.KO, closePaymentRequestCaptor.value.outcome)
      // check additionalPaymentInformations
      assertNull(closePaymentRequestCaptor.value.additionalPaymentInformations)
      // check transactionDetails
      assertEquals(
        UserDto.TypeEnum.GUEST, closePaymentRequestCaptor.value.transactionDetails.user.type)
      assertEquals(
        TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_CANCELED.status,
        closePaymentRequestCaptor.value.transactionDetails.transaction.transactionStatus)
      assertEquals(
        Transaction.ClientId.CHECKOUT.name,
        closePaymentRequestCaptor.value.transactionDetails.info.clientId)
      assertEquals(TIPO_VERSAMENTO_CP, closePaymentRequestCaptor.value.transactionDetails.info.type)
      assertEquals(
        closePaymentRequestCaptor.value.transactionDetails.transaction.amount,
        closePaymentRequestCaptor.value.transactionDetails.transaction.grandTotal)
      assertNull(closePaymentRequestCaptor.value.transactionDetails.transaction.fee)
      assertNotNull(closePaymentRequestCaptor.value.transactionDetails.transaction.amount)
      assertNotNull(closePaymentRequestCaptor.value.transactionDetails.transaction.grandTotal)
      assertEquals(amount, closePaymentRequestCaptor.value.transactionDetails.transaction.amount)
      assertEquals(
        amount, closePaymentRequestCaptor.value.transactionDetails.transaction.grandTotal)
      assertNotNull(closePaymentRequestCaptor.value.transactionDetails.transaction.creationDate)
    }

  @Test
  fun `closePayment throws BadTransactionStatusException for only transaction activated event `() =
    runTest {
      val transactionId = TRANSACTION_ID
      val transactionOutcome = ClosePaymentOutcome.OK

      val activatedEvent = transactionActivateEvent() as TransactionEvent<Any>
      val events = listOf(activatedEvent)
      /* preconditions */
      given(
          transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(
            TRANSACTION_ID))
        .willReturn(events.toFlux())

      /* test */

      assertThrows<BadTransactionStatusException> {
        nodeService.closePayment(TransactionId(transactionId), transactionOutcome)
      }
    }

  companion object {
    @JvmStatic
    private fun closePaymentDateFormat() =
      Stream.of(
        Arguments.of("2023-05-01T23:59:59.000Z", "2023-05-02T01:59:59"),
        Arguments.of("2023-12-01T23:59:59.000Z", "2023-12-02T00:59:59"))
  }

  @ParameterizedTest
  @MethodSource("closePaymentDateFormat")
  fun `ClosePaymentRequestV2Dto for close payment OK has additional properties and transaction details valued correctly for NPG payment gateway`(
    timestampOperation: String,
    expectedLocalDate: String
  ) = runTest {
    val transactionOutcome = ClosePaymentOutcome.OK

    val activatedEvent = transactionActivateEvent()
    val authEvent =
      transactionAuthorizationRequestedEvent(
        TransactionAuthorizationRequestData.PaymentGateway.NPG,
        npgTransactionGatewayAuthorizationRequestedData())
    val authCompletedEvent =
      transactionAuthorizationCompletedEvent(
        npgTransactionGatewayAuthorizationData(OperationResultDto.EXECUTED))
    val closureRequestedEvent = transactionClosureRequestedEvent()
    val closureError = transactionClosureErrorEvent()
    val transactionId = activatedEvent.transactionId
    val nodoTimestampOperation = OffsetDateTime.parse(timestampOperation)
    authCompletedEvent.data.timestampOperation = nodoTimestampOperation.toString()
    val events =
      listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
        as List<TransactionEvent<Any>>

    val closePaymentResponse =
      ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }

    val fee = authEvent.data.fee
    val amount = authEvent.data.amount
    val totalAmount = amount + fee

    val feeEuro = EuroUtils.euroCentsToEuro(fee)
    val totalAmountEuro = EuroUtils.euroCentsToEuro(totalAmount)

    val feeEuroCents = BigDecimal(fee)
    val amountEuroCents = BigDecimal(amount)
    val totalAmountEuroCents = BigDecimal(totalAmount)

    /* preconditions */
    given(
        transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(TRANSACTION_ID))
      .willReturn(events.toFlux())

    given(nodeClient.closePayment(capture(closePaymentRequestCaptor)))
      .willReturn(Mono.just(closePaymentResponse))

    given(confidentialDataUtils.eCommerceDecrypt(eq(activatedEvent.data.email), any()))
      .willReturn(Mono.just(Email(EMAIL_STRING)))

    /* test */
    assertEquals(
      closePaymentResponse,
      nodeService.closePayment(TransactionId(transactionId), transactionOutcome))

    val expectedTimestamp = expectedLocalDate

    val expectedOutcome =
      authCompletedEvent.data.transactionGatewayAuthorizationData.let {
        when (it) {
          is NpgTransactionGatewayAuthorizationData ->
            if (it.operationResult == OperationResultDto.EXECUTED) {
              OutcomePaymentGatewayEnum.OK
            } else {
              OutcomePaymentGatewayEnum.KO
            }
          is RedirectTransactionGatewayAuthorizationData ->
            if (it.outcome == RedirectTransactionGatewayAuthorizationData.Outcome.OK) {
              OutcomePaymentGatewayEnum.OK
            } else {
              OutcomePaymentGatewayEnum.KO
            }
        }
      }

    // Check close payment request information
    val expected =
      CardClosePaymentRequestV2Dto().apply {
        outcome = CardClosePaymentRequestV2Dto.OutcomeEnum.OK
        this.transactionId = transactionId
        paymentTokens =
          activatedEvent.data.paymentNotices.map { paymentNotice -> paymentNotice.paymentToken }
        this.timestampOperation = OffsetDateTime.parse(authCompletedEvent.data.timestampOperation)
        this.fee = feeEuro
        idPSP = authEvent.data.pspId
        idChannel = authEvent.data.pspChannelCode
        idBrokerPSP = authEvent.data.brokerName
        paymentMethod = authEvent.data.paymentTypeCode
        this.totalAmount = totalAmountEuro
        transactionDetails =
          TransactionDetailsDto().apply {
            transaction =
              TransactionDto().apply {
                transactionStatus =
                  TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_CONFIRMED.status
                this.transactionId = transactionId
                this.fee = feeEuroCents
                this.amount = amountEuroCents
                grandTotal = totalAmountEuroCents
                this.errorCode = errorCode
                rrn = authCompletedEvent.data.rrn
                creationDate = ZonedDateTime.parse(activatedEvent.creationDate).toOffsetDateTime()
                psp =
                  PspDto().apply {
                    idPsp = authEvent.data.pspId
                    brokerName = authEvent.data.brokerName
                    idChannel = authEvent.data.pspChannelCode
                    businessName = authEvent.data.pspBusinessName
                    pspOnUs = authEvent.data.isPspOnUs
                  }
                authorizationCode = authCompletedEvent.data.authorizationCode
                this.timestampOperation = authCompletedEvent.data.timestampOperation
                paymentGateway = authEvent.data.paymentGateway.name
              }
            user = UserDto().apply { type = UserDto.TypeEnum.GUEST }
            info =
              InfoDto().apply {
                type = authEvent.data.paymentTypeCode
                clientId = Transaction.ClientId.CHECKOUT.name
                brand =
                  (authEvent.data.transactionGatewayAuthorizationRequestedData
                      as NpgTransactionGatewayAuthorizationRequestedData)
                    .brand
                brandLogo =
                  (authEvent.data.transactionGatewayAuthorizationRequestedData
                      as NpgTransactionGatewayAuthorizationRequestedData)
                    .logo
                    .toString()
                paymentMethodName = authEvent.data.paymentMethodName
              }
          }
        additionalPaymentInformations =
          CardAdditionalPaymentInformationsDto().apply {
            authorizationCode = authCompletedEvent.data.authorizationCode
            this.fee = feeEuro.toString()
            outcomePaymentGateway = expectedOutcome
            rrn = authCompletedEvent.data.rrn
            this.timestampOperation = expectedTimestamp
            this.totalAmount = totalAmountEuro.toString()
            this.email = EMAIL_STRING
          }
      }

    assertEquals(expected, closePaymentRequestCaptor.value)
  }

  @Test
  fun `ClosePaymentRequestV2Dto for close payment OK authorization KO has additional properties and transaction details valued correctly for NPG payment gateway`() =
    runTest {
      val transactionOutcome = ClosePaymentOutcome.KO

      val activatedEvent = transactionActivateEvent()
      val authEvent =
        transactionAuthorizationRequestedEvent(
          TransactionAuthorizationRequestData.PaymentGateway.NPG,
          npgTransactionGatewayAuthorizationRequestedData())
      val authCompletedEvent =
        transactionAuthorizationCompletedEvent(
          npgTransactionGatewayAuthorizationData(OperationResultDto.DECLINED))
      val closureRequestedEvent = transactionClosureRequestedEvent()
      val closureError = transactionClosureErrorEvent()
      val transactionId = activatedEvent.transactionId
      val events =
        listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
          as List<TransactionEvent<Any>>

      val closePaymentResponse =
        ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }

      val fee = authEvent.data.fee
      val amount = authEvent.data.amount
      val totalAmount = amount + fee

      val feeEuroCents = BigDecimal(fee)
      val amountEuroCents = BigDecimal(amount)
      val totalAmountEuroCents = BigDecimal(totalAmount)

      /* preconditions */
      given(
          transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(
            TRANSACTION_ID))
        .willReturn(events.toFlux())

      given(nodeClient.closePayment(capture(closePaymentRequestCaptor)))
        .willReturn(Mono.just(closePaymentResponse))

      /* test */
      assertEquals(
        closePaymentResponse,
        nodeService.closePayment(TransactionId(transactionId), transactionOutcome))

      // Check close payment request information
      val expected =
        CardClosePaymentRequestV2Dto().apply {
          outcome = CardClosePaymentRequestV2Dto.OutcomeEnum.KO
          this.transactionId = transactionId
          paymentTokens =
            activatedEvent.data.paymentNotices.map { paymentNotice -> paymentNotice.paymentToken }
          transactionDetails =
            TransactionDetailsDto().apply {
              transaction =
                TransactionDto().apply {
                  transactionStatus =
                    TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_DENIED.status
                  this.transactionId = transactionId
                  this.fee = feeEuroCents
                  this.amount = amountEuroCents
                  grandTotal = totalAmountEuroCents
                  this.errorCode = errorCode
                  rrn = authCompletedEvent.data.rrn
                  creationDate = ZonedDateTime.parse(activatedEvent.creationDate).toOffsetDateTime()
                  psp =
                    PspDto().apply {
                      idPsp = authEvent.data.pspId
                      brokerName = authEvent.data.brokerName
                      idChannel = authEvent.data.pspChannelCode
                      businessName = authEvent.data.pspBusinessName
                      pspOnUs = authEvent.data.isPspOnUs
                    }
                  authorizationCode = null
                  timestampOperation = authCompletedEvent.data.timestampOperation
                  paymentGateway = authEvent.data.paymentGateway.name
                }
              user = UserDto().apply { type = UserDto.TypeEnum.GUEST }
              info =
                InfoDto().apply {
                  type = authEvent.data.paymentTypeCode
                  clientId = Transaction.ClientId.CHECKOUT.name
                  brand =
                    (authEvent.data.transactionGatewayAuthorizationRequestedData
                        as NpgTransactionGatewayAuthorizationRequestedData)
                      .brand
                  brandLogo =
                    (authEvent.data.transactionGatewayAuthorizationRequestedData
                        as NpgTransactionGatewayAuthorizationRequestedData)
                      .logo
                      .toString()
                  paymentMethodName = authEvent.data.paymentMethodName
                }
            }
          additionalPaymentInformations = null
        }

      assertEquals(expected, closePaymentRequestCaptor.value)
    }

  @Test
  fun `ClosePaymentRequestV2Dto for close payment KO for authorization KO has additional properties and transaction details valued correctly for NPG gateway`() =
    runTest {
      val transactionOutcome = ClosePaymentOutcome.KO

      val activatedEvent = transactionActivateEvent()
      val authEvent =
        transactionAuthorizationRequestedEvent(
          TransactionAuthorizationRequestData.PaymentGateway.NPG,
          npgTransactionGatewayAuthorizationRequestedData())
      val authCompletedEvent =
        transactionAuthorizationCompletedEvent(
          npgTransactionGatewayAuthorizationData(OperationResultDto.DECLINED))
      val closureRequestedEvent = transactionClosureRequestedEvent()
      val closureError = transactionClosureErrorEvent()
      val transactionId = activatedEvent.transactionId
      val events =
        listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
          as List<TransactionEvent<Any>>

      val closePaymentResponse =
        ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }

      val fee = authEvent.data.fee
      val amount = authEvent.data.amount
      val totalAmount = amount + fee

      val feeEuroCents = BigDecimal(fee)
      val amountEuroCents = BigDecimal(amount)
      val totalAmountEuroCents = BigDecimal(totalAmount)

      /* preconditions */
      given(
          transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(
            TRANSACTION_ID))
        .willReturn(events.toFlux())

      given(nodeClient.closePayment(capture(closePaymentRequestCaptor)))
        .willReturn(Mono.just(closePaymentResponse))

      val expected =
        CardClosePaymentRequestV2Dto().apply {
          outcome = CardClosePaymentRequestV2Dto.OutcomeEnum.KO
          this.transactionId = transactionId
          paymentTokens =
            activatedEvent.data.paymentNotices.map { paymentNotice -> paymentNotice.paymentToken }
          transactionDetails =
            TransactionDetailsDto().apply {
              transaction =
                TransactionDto().apply {
                  transactionStatus =
                    TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_DENIED.status
                  this.transactionId = transactionId
                  this.fee = feeEuroCents
                  this.amount = amountEuroCents
                  grandTotal = totalAmountEuroCents
                  this.errorCode = errorCode
                  rrn = authCompletedEvent.data.rrn
                  creationDate = ZonedDateTime.parse(activatedEvent.creationDate).toOffsetDateTime()
                  psp =
                    PspDto().apply {
                      idPsp = authEvent.data.pspId
                      brokerName = authEvent.data.brokerName
                      idChannel = authEvent.data.pspChannelCode
                      businessName = authEvent.data.pspBusinessName
                      pspOnUs = authEvent.data.isPspOnUs
                    }
                  authorizationCode = null
                  timestampOperation = authCompletedEvent.data.timestampOperation
                  paymentGateway = authEvent.data.paymentGateway.name
                }
              user = UserDto().apply { type = UserDto.TypeEnum.GUEST }
              info =
                InfoDto().apply {
                  type = authEvent.data.paymentTypeCode
                  clientId = Transaction.ClientId.CHECKOUT.name
                  brand =
                    (authEvent.data.transactionGatewayAuthorizationRequestedData
                        as NpgTransactionGatewayAuthorizationRequestedData)
                      .brand!!
                  brandLogo =
                    (authEvent.data.transactionGatewayAuthorizationRequestedData
                        as NpgTransactionGatewayAuthorizationRequestedData)
                      .logo
                      .toString()
                  paymentMethodName = authEvent.data.paymentMethodName
                }
            }
          additionalPaymentInformations = null
        }

      /* test */
      assertEquals(
        closePaymentResponse,
        nodeService.closePayment(TransactionId(transactionId), transactionOutcome))

      assertEquals(expected, closePaymentRequestCaptor.value)
    }

  @Test
  fun `ClosePaymentRequestV2Dto for close payment OK for cancelled transaction is correct for NPG gateway`() =
    runTest {
      val transactionOutcome = ClosePaymentOutcome.KO

      val activatedEvent = transactionActivateEvent()
      val authEvent =
        transactionAuthorizationRequestedEvent(
          TransactionAuthorizationRequestData.PaymentGateway.NPG,
          npgTransactionGatewayAuthorizationRequestedData())
      val authCompletedEvent =
        transactionAuthorizationCompletedEvent(
          npgTransactionGatewayAuthorizationData(OperationResultDto.DECLINED))
      val closureRequestedEvent = transactionClosureRequestedEvent()
      val closureError = transactionClosureErrorEvent()
      val transactionId = activatedEvent.transactionId
      val events =
        listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
          as List<TransactionEvent<Any>>

      val closePaymentResponse =
        ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }

      val fee = authEvent.data.fee
      val amount = authEvent.data.amount
      val totalAmount = amount + fee

      val feeEuroCents = BigDecimal(fee)
      val amountEuroCents = BigDecimal(amount)
      val totalAmountEuroCents = BigDecimal(totalAmount)

      /* preconditions */
      given(
          transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(
            TRANSACTION_ID))
        .willReturn(events.toFlux())

      given(nodeClient.closePayment(capture(closePaymentRequestCaptor)))
        .willReturn(Mono.just(closePaymentResponse))

      val expected =
        CardClosePaymentRequestV2Dto().apply {
          outcome = CardClosePaymentRequestV2Dto.OutcomeEnum.KO
          this.transactionId = transactionId
          paymentTokens =
            activatedEvent.data.paymentNotices.map { paymentNotice -> paymentNotice.paymentToken }
          transactionDetails =
            TransactionDetailsDto().apply {
              transaction =
                TransactionDto().apply {
                  transactionStatus =
                    TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_DENIED.status
                  this.transactionId = transactionId
                  this.fee = feeEuroCents
                  this.amount = amountEuroCents
                  grandTotal = totalAmountEuroCents
                  this.errorCode = errorCode
                  rrn = authCompletedEvent.data.rrn
                  creationDate = ZonedDateTime.parse(activatedEvent.creationDate).toOffsetDateTime()
                  psp =
                    PspDto().apply {
                      idPsp = authEvent.data.pspId
                      brokerName = authEvent.data.brokerName
                      idChannel = authEvent.data.pspChannelCode
                      businessName = authEvent.data.pspBusinessName
                      pspOnUs = authEvent.data.isPspOnUs
                    }
                  authorizationCode = null
                  timestampOperation = authCompletedEvent.data.timestampOperation
                  paymentGateway = authEvent.data.paymentGateway.name
                }
              user = UserDto().apply { type = UserDto.TypeEnum.GUEST }
              info =
                InfoDto().apply {
                  type = authEvent.data.paymentTypeCode
                  clientId = Transaction.ClientId.CHECKOUT.name
                  brand =
                    (authEvent.data.transactionGatewayAuthorizationRequestedData
                        as NpgTransactionGatewayAuthorizationRequestedData)
                      .brand!!
                  brandLogo =
                    (authEvent.data.transactionGatewayAuthorizationRequestedData
                        as NpgTransactionGatewayAuthorizationRequestedData)
                      .logo
                      .toString()
                  paymentMethodName = authEvent.data.paymentMethodName
                }
            }
          additionalPaymentInformations = null
        }

      /* test */
      assertEquals(
        closePaymentResponse,
        nodeService.closePayment(TransactionId(transactionId), transactionOutcome))

      assertEquals(expected, closePaymentRequestCaptor.value)
    }

  @Test
  fun `closePayment returns successfully for close payment after authorization Completed from NPG KO`() =
    runTest {
      val activatedEvent = transactionActivateEvent()
      val authEvent =
        transactionAuthorizationRequestedEvent(
          TransactionAuthorizationRequestData.PaymentGateway.NPG,
          npgTransactionGatewayAuthorizationRequestedData())
      val authCompletedEvent =
        transactionAuthorizationCompletedEvent(
          NpgTransactionGatewayAuthorizationData(
            OperationResultDto.EXECUTED, "operationId", "paymentEndTOEndId", null, null))
      val closureRequestedEvent = transactionClosureRequestedEvent()
      val closureError = transactionClosureErrorEvent()
      val transactionId = activatedEvent.transactionId
      val events =
        listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
          as List<TransactionEvent<Any>>

      val npgOutcome = ClosePaymentOutcome.KO

      val closePaymentResponse =
        ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }

      /* preconditions */
      given(
          transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(
            TRANSACTION_ID))
        .willReturn(events.toFlux())

      given(nodeClient.closePayment(any())).willReturn(Mono.just(closePaymentResponse))

      given(confidentialDataUtils.eCommerceDecrypt(eq(activatedEvent.data.email), any()))
        .willReturn(Mono.just(Email(EMAIL_STRING)))

      /* test */
      assertEquals(
        closePaymentResponse, nodeService.closePayment(TransactionId(transactionId), npgOutcome))
    }

  @Test
  fun `closePayment returns error for close payment missing authorization completed event`() =
    runTest {
      val activatedEvent = transactionActivateEvent()
      val authEvent = transactionAuthorizationRequestedEvent()

      val transactionId = activatedEvent.transactionId
      val events = listOf(activatedEvent, authEvent) as List<TransactionEvent<Any>>
      val transactionOutcome = ClosePaymentOutcome.OK

      val closePaymentResponse =
        ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }

      /* preconditions */
      given(
          transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(
            TRANSACTION_ID))
        .willReturn(events.toFlux())

      given(nodeClient.closePayment(any())).willReturn(Mono.just(closePaymentResponse))

      /* test */
      assertThrows<BadTransactionStatusException> {
        nodeService.closePayment(TransactionId(transactionId), transactionOutcome)
      }
    }

  @ParameterizedTest
  @MethodSource("closePaymentDateFormat")
  fun `ClosePaymentRequestV2Dto for close payment OK has additional properties and transaction details valued correctly for REDIRECT payment gateway`(
    timestampOperation: String,
    expectedLocalDate: String
  ) = runTest {
    val transactionOutcome = ClosePaymentOutcome.OK
    val redirectTransactionGatewayAuthorizationRequestedData =
      redirectTransactionGatewayAuthorizationRequestedData()
        as RedirectTransactionGatewayAuthorizationRequestedData
    val redirectTransactionGatewayAuthorizationData =
      redirectTransactionGatewayAuthorizationData(
        RedirectTransactionGatewayAuthorizationData.Outcome.OK, "")
        as RedirectTransactionGatewayAuthorizationData

    val activatedEvent = transactionActivateEvent()
    val authEvent =
      transactionAuthorizationRequestedEvent(
        TransactionAuthorizationRequestData.PaymentGateway.REDIRECT,
        redirectTransactionGatewayAuthorizationRequestedData)
    val authCompletedEvent =
      transactionAuthorizationCompletedEvent(redirectTransactionGatewayAuthorizationData)
    val closureRequestedEvent = transactionClosureRequestedEvent()
    val closureError = transactionClosureErrorEvent()
    val transactionId = activatedEvent.transactionId
    val nodoTimestampOperation = OffsetDateTime.parse(timestampOperation)
    authCompletedEvent.data.timestampOperation = nodoTimestampOperation.toString()
    val events =
      listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
        as List<TransactionEvent<Any>>

    val closePaymentResponse =
      ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }

    /* preconditions */
    given(
        transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(TRANSACTION_ID))
      .willReturn(events.toFlux())

    given(nodeClient.closePayment(capture(redirectClosePaymentRequestCaptor)))
      .willReturn(Mono.just(closePaymentResponse))

    val fee = authEvent.data.fee
    val amount = authEvent.data.amount
    val totalAmount = amount + fee

    val feeEuro = EuroUtils.euroCentsToEuro(fee)
    val totalAmountEuro = EuroUtils.euroCentsToEuro(totalAmount)

    val feeEuroCents = BigDecimal(fee)
    val amountEuroCents = BigDecimal(amount)
    val totalAmountEuroCents = BigDecimal(totalAmount)

    /* test */
    assertEquals(
      closePaymentResponse,
      nodeService.closePayment(TransactionId(transactionId), transactionOutcome))
    val expectedTimestamp = expectedLocalDate

    val expected =
      RedirectClosePaymentRequestV2Dto().apply {
        outcome = RedirectClosePaymentRequestV2Dto.OutcomeEnum.OK
        this.transactionId = transactionId
        paymentTokens =
          activatedEvent.data.paymentNotices.map { paymentNotice -> paymentNotice.paymentToken }
        this.timestampOperation = OffsetDateTime.parse(authCompletedEvent.data.timestampOperation)
        this.fee = feeEuro
        idPSP = authEvent.data.pspId
        idChannel = authEvent.data.pspChannelCode
        idBrokerPSP = authEvent.data.brokerName
        paymentMethod = authEvent.data.paymentTypeCode
        this.totalAmount = totalAmountEuro
        transactionDetails =
          TransactionDetailsDto().apply {
            transaction =
              TransactionDto().apply {
                transactionStatus =
                  TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_CONFIRMED.status
                this.transactionId = transactionId
                this.fee = feeEuroCents
                this.amount = amountEuroCents
                grandTotal = totalAmountEuroCents
                this.errorCode = errorCode
                rrn = authCompletedEvent.data.rrn
                creationDate = ZonedDateTime.parse(activatedEvent.creationDate).toOffsetDateTime()
                psp =
                  PspDto().apply {
                    idPsp = authEvent.data.pspId
                    brokerName = authEvent.data.brokerName
                    idChannel = authEvent.data.pspChannelCode
                    businessName = authEvent.data.pspBusinessName
                    pspOnUs = authEvent.data.isPspOnUs
                  }
                authorizationCode = authCompletedEvent.data.authorizationCode
                this.timestampOperation = authCompletedEvent.data.timestampOperation
                paymentGateway = authEvent.data.paymentGateway.name
              }
            user = UserDto().apply { type = UserDto.TypeEnum.GUEST }
            info =
              InfoDto().apply {
                type = authEvent.data.paymentTypeCode
                clientId = Transaction.ClientId.CHECKOUT.name
                brand = authEvent.data.paymentTypeCode
                brandLogo =
                  (authEvent.data.transactionGatewayAuthorizationRequestedData
                      as RedirectTransactionGatewayAuthorizationRequestedData)
                    .logo
                    .toString()
                paymentMethodName = authEvent.data.paymentMethodName
              }
          }
        additionalPaymentInformations =
          RedirectAdditionalPaymentInformationsDto().apply {
            this.timestampOperation = expectedTimestamp
            idPSPTransaction = authEvent.data.authorizationRequestId
            this.fee = feeEuro.toString()
            this.totalAmount = totalAmountEuro.toString()
            authorizationCode = authCompletedEvent.data.authorizationCode
            idTransaction = TRANSACTION_ID
          }
      }

    assertEquals(expected, redirectClosePaymentRequestCaptor.value)
  }

  @Test
  fun `ClosePaymentRequestV2Dto for close payment KO for cancelled transaction is correct for REDIRECT payment gateway`() =
    runTest {
      val transactionOutcome = ClosePaymentOutcome.KO
      val redirectTransactionGatewayAuthorizationRequestedData =
        redirectTransactionGatewayAuthorizationRequestedData()
          as RedirectTransactionGatewayAuthorizationRequestedData
      val redirectTransactionGatewayAuthorizationData =
        redirectTransactionGatewayAuthorizationData(
          RedirectTransactionGatewayAuthorizationData.Outcome.KO, "errorCode")
          as RedirectTransactionGatewayAuthorizationData

      val activatedEvent = transactionActivateEvent()
      val authEvent =
        transactionAuthorizationRequestedEvent(
          TransactionAuthorizationRequestData.PaymentGateway.REDIRECT,
          redirectTransactionGatewayAuthorizationRequestedData)
      val authCompletedEvent =
        transactionAuthorizationCompletedEvent(redirectTransactionGatewayAuthorizationData)
      val closureRequestedEvent = transactionClosureRequestedEvent()
      val closureError = transactionClosureErrorEvent()
      val transactionId = activatedEvent.transactionId
      val events =
        listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
          as List<TransactionEvent<Any>>

      val closePaymentResponse =
        ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }

      /* preconditions */
      given(
          transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(
            TRANSACTION_ID))
        .willReturn(events.toFlux())

      given(nodeClient.closePayment(capture(redirectClosePaymentRequestCaptor)))
        .willReturn(Mono.just(closePaymentResponse))

      val fee = authEvent.data.fee
      val amount = authEvent.data.amount
      val totalAmount = amount + fee

      val feeEuroCents = BigDecimal(fee)
      val amountEuroCents = BigDecimal(amount)
      val totalAmountEuroCents = BigDecimal(totalAmount)

      /* test */
      assertEquals(
        closePaymentResponse,
        nodeService.closePayment(TransactionId(transactionId), transactionOutcome))

      val expected =
        RedirectClosePaymentRequestV2Dto().apply {
          outcome = RedirectClosePaymentRequestV2Dto.OutcomeEnum.KO
          this.transactionId = transactionId
          paymentTokens =
            activatedEvent.data.paymentNotices.map { paymentNotice -> paymentNotice.paymentToken }
          transactionDetails =
            TransactionDetailsDto().apply {
              transaction =
                TransactionDto().apply {
                  transactionStatus =
                    TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_DENIED.status
                  this.transactionId = transactionId
                  this.fee = feeEuroCents
                  this.amount = amountEuroCents
                  grandTotal = totalAmountEuroCents
                  this.errorCode =
                    (authCompletedEvent.data.transactionGatewayAuthorizationData
                        as RedirectTransactionGatewayAuthorizationData)
                      .errorCode
                  rrn = authCompletedEvent.data.rrn
                  creationDate = ZonedDateTime.parse(activatedEvent.creationDate).toOffsetDateTime()
                  psp =
                    PspDto().apply {
                      idPsp = authEvent.data.pspId
                      brokerName = authEvent.data.brokerName
                      idChannel = authEvent.data.pspChannelCode
                      businessName = authEvent.data.pspBusinessName
                      pspOnUs = authEvent.data.isPspOnUs
                    }
                  authorizationCode = null
                  timestampOperation = authCompletedEvent.data.timestampOperation
                  paymentGateway = authEvent.data.paymentGateway.name
                }
              user = UserDto().apply { type = UserDto.TypeEnum.GUEST }
              info =
                InfoDto().apply {
                  type = authEvent.data.paymentTypeCode
                  clientId = Transaction.ClientId.CHECKOUT.name
                  brand = authEvent.data.paymentTypeCode
                  brandLogo =
                    (authEvent.data.transactionGatewayAuthorizationRequestedData
                        as RedirectTransactionGatewayAuthorizationRequestedData)
                      .logo
                      .toString()
                  paymentMethodName = authEvent.data.paymentMethodName
                }
            }
          additionalPaymentInformations = null
        }

      assertEquals(expected, redirectClosePaymentRequestCaptor.value)
    }

  @Test
  fun `ClosePaymentRequestV2Dto for close payment KO for authorization KO is correct for REDIRECT payment gateway`() =
    runTest {
      val transactionOutcome = ClosePaymentOutcome.KO
      val redirectTransactionGatewayAuthorizationRequestedData =
        redirectTransactionGatewayAuthorizationRequestedData()
          as RedirectTransactionGatewayAuthorizationRequestedData
      val redirectTransactionGatewayAuthorizationData =
        redirectTransactionGatewayAuthorizationData(
          RedirectTransactionGatewayAuthorizationData.Outcome.KO, "errorCode")
          as RedirectTransactionGatewayAuthorizationData

      val activatedEvent = transactionActivateEvent()
      val authEvent =
        transactionAuthorizationRequestedEvent(
          TransactionAuthorizationRequestData.PaymentGateway.REDIRECT,
          redirectTransactionGatewayAuthorizationRequestedData)
      val authCompletedEvent =
        transactionAuthorizationCompletedEvent(redirectTransactionGatewayAuthorizationData)
      val closureRequestedEvent = transactionClosureRequestedEvent()
      val closureError = transactionClosureErrorEvent()
      val transactionId = activatedEvent.transactionId
      val events =
        listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
          as List<TransactionEvent<Any>>

      val closePaymentResponse =
        ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }

      /* preconditions */
      given(
          transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(
            TRANSACTION_ID))
        .willReturn(events.toFlux())

      given(nodeClient.closePayment(capture(redirectClosePaymentRequestCaptor)))
        .willReturn(Mono.just(closePaymentResponse))

      val fee = authEvent.data.fee
      val amount = authEvent.data.amount
      val totalAmount = amount + fee

      val feeEuroCents = BigDecimal(fee)
      val amountEuroCents = BigDecimal(amount)
      val totalAmountEuroCents = BigDecimal(totalAmount)

      /* test */
      assertEquals(
        closePaymentResponse,
        nodeService.closePayment(TransactionId(transactionId), transactionOutcome))

      val expected =
        RedirectClosePaymentRequestV2Dto().apply {
          outcome = RedirectClosePaymentRequestV2Dto.OutcomeEnum.KO
          this.transactionId = transactionId
          paymentTokens =
            activatedEvent.data.paymentNotices.map { paymentNotice -> paymentNotice.paymentToken }
          transactionDetails =
            TransactionDetailsDto().apply {
              transaction =
                TransactionDto().apply {
                  transactionStatus =
                    TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_DENIED.status
                  this.transactionId = transactionId
                  this.fee = feeEuroCents
                  this.amount = amountEuroCents
                  grandTotal = totalAmountEuroCents
                  this.errorCode =
                    (authCompletedEvent.data.transactionGatewayAuthorizationData
                        as RedirectTransactionGatewayAuthorizationData)
                      .errorCode
                  rrn = authCompletedEvent.data.rrn
                  creationDate = ZonedDateTime.parse(activatedEvent.creationDate).toOffsetDateTime()
                  psp =
                    PspDto().apply {
                      idPsp = authEvent.data.pspId
                      brokerName = authEvent.data.brokerName
                      idChannel = authEvent.data.pspChannelCode
                      businessName = authEvent.data.pspBusinessName
                      pspOnUs = authEvent.data.isPspOnUs
                    }
                  authorizationCode = null
                  timestampOperation = authCompletedEvent.data.timestampOperation
                  paymentGateway = authEvent.data.paymentGateway.name
                }
              user = UserDto().apply { type = UserDto.TypeEnum.GUEST }
              info =
                InfoDto().apply {
                  type = authEvent.data.paymentTypeCode
                  clientId = Transaction.ClientId.CHECKOUT.name
                  brand = authEvent.data.paymentTypeCode
                  brandLogo =
                    (authEvent.data.transactionGatewayAuthorizationRequestedData
                        as RedirectTransactionGatewayAuthorizationRequestedData)
                      .logo
                      .toString()
                  paymentMethodName = authEvent.data.paymentMethodName
                }
            }
          additionalPaymentInformations = null
        }

      assertEquals(expected, redirectClosePaymentRequestCaptor.value)
    }

  @ParameterizedTest
  @MethodSource("closePaymentDateFormat")
  fun `ClosePaymentRequestV2Dto for close payment OK has additional properties and transaction details valued correctly for Satispay method`(
    timestampOperation: String,
    expectedLocalDate: String
  ) = runTest {
    val transactionOutcome = ClosePaymentOutcome.OK
    val satispayTransactionGatewayAuthorizationRequestedData =
      NpgTransactionGatewayAuthorizationRequestedData(
        LOGO_URI,
        NpgClient.PaymentMethod.SATISPAY.toString(),
        "npgSessionId",
        "npgConfirmPaymentSessionId",
        null)
    val satispayTransactionGatewayAuthorizationData =
      npgTransactionGatewayAuthorizationData(OperationResultDto.EXECUTED)

    val activatedEvent = transactionActivateEvent()
    val authEvent =
      TransactionAuthorizationRequestedEvent(
        TRANSACTION_ID,
        TransactionAuthorizationRequestData(
          100,
          10,
          "paymentInstrumentId",
          "pspId",
          PaymentCode.SATY.name,
          "brokerName",
          "pspChannelCode",
          "paymentMethodName",
          "pspBusinessName",
          false,
          AUTHORIZATION_REQUEST_ID,
          TransactionAuthorizationRequestData.PaymentGateway.NPG,
          "paymentMethodDescription",
          satispayTransactionGatewayAuthorizationRequestedData))
    val authCompletedEvent =
      transactionAuthorizationCompletedEvent(satispayTransactionGatewayAuthorizationData)
    val closureRequestedEvent = transactionClosureRequestedEvent()
    val closureError = transactionClosureErrorEvent()
    val transactionId = activatedEvent.transactionId
    val nodoTimestampOperation = OffsetDateTime.parse(timestampOperation)
    authCompletedEvent.data.timestampOperation = nodoTimestampOperation.toString()
    val events =
      listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
        as List<TransactionEvent<Any>>

    val closePaymentResponse =
      ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }

    /* preconditions */
    given(
        transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(TRANSACTION_ID))
      .willReturn(events.toFlux())
    given(confidentialDataUtils.eCommerceDecrypt(eq(activatedEvent.data.email), any()))
      .willReturn(Mono.just(Email(EMAIL_STRING)))

    given(nodeClient.closePayment(capture(satispayClosePaymentRequestCaptor)))
      .willReturn(Mono.just(closePaymentResponse))

    val fee = authEvent.data.fee
    val amount = authEvent.data.amount
    val totalAmount = amount + fee

    val feeEuro = EuroUtils.euroCentsToEuro(fee)
    val totalAmountEuro = EuroUtils.euroCentsToEuro(totalAmount)

    val feeEuroCents = BigDecimal(fee)
    val amountEuroCents = BigDecimal(amount)
    val totalAmountEuroCents = BigDecimal(totalAmount)

    /* test */
    assertEquals(
      closePaymentResponse,
      nodeService.closePayment(TransactionId(transactionId), transactionOutcome))
    val expectedTimestamp = expectedLocalDate

    val expected =
      SatispayClosePaymentRequestV2Dto().apply {
        outcome = SatispayClosePaymentRequestV2Dto.OutcomeEnum.OK
        this.transactionId = transactionId
        paymentTokens =
          activatedEvent.data.paymentNotices.map { paymentNotice -> paymentNotice.paymentToken }
        this.timestampOperation = OffsetDateTime.parse(authCompletedEvent.data.timestampOperation)
        this.fee = feeEuro
        idPSP = authEvent.data.pspId
        idChannel = authEvent.data.pspChannelCode
        idBrokerPSP = authEvent.data.brokerName
        paymentMethod = authEvent.data.paymentTypeCode
        this.totalAmount = totalAmountEuro
        transactionDetails =
          TransactionDetailsDto().apply {
            transaction =
              TransactionDto().apply {
                transactionStatus =
                  TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_CONFIRMED.status
                this.transactionId = transactionId
                this.fee = feeEuroCents
                this.amount = amountEuroCents
                grandTotal = totalAmountEuroCents
                this.errorCode = errorCode
                rrn = authCompletedEvent.data.rrn
                creationDate = ZonedDateTime.parse(activatedEvent.creationDate).toOffsetDateTime()
                psp =
                  PspDto().apply {
                    idPsp = authEvent.data.pspId
                    brokerName = authEvent.data.brokerName
                    idChannel = authEvent.data.pspChannelCode
                    businessName = authEvent.data.pspBusinessName
                    pspOnUs = authEvent.data.isPspOnUs
                  }
                authorizationCode = authCompletedEvent.data.authorizationCode
                this.timestampOperation = authCompletedEvent.data.timestampOperation
                paymentGateway = authEvent.data.paymentGateway.name
              }
            user = UserDto().apply { type = UserDto.TypeEnum.GUEST }
            info =
              InfoDto().apply {
                type = authEvent.data.paymentTypeCode
                clientId = Transaction.ClientId.CHECKOUT.name
                brand = authEvent.data.paymentTypeCode
                brandLogo =
                  (authEvent.data.transactionGatewayAuthorizationRequestedData
                      as NpgTransactionGatewayAuthorizationRequestedData)
                    .logo
                    .toString()
                paymentMethodName = authEvent.data.paymentMethodName
              }
          }
        additionalPaymentInformations =
          SatispayAdditionalPaymentInformationsDto().apply {
            this.satispayTransactionId =
              (authCompletedEvent.data.transactionGatewayAuthorizationData
                  as NpgTransactionGatewayAuthorizationData)
                .paymentEndToEndId
            this.timestampOperation = expectedTimestamp
            this.fee = feeEuro.toString()
            this.totalAmount = totalAmountEuro.toString()
            this.email = EMAIL_STRING
          }
      }

    assertEquals(expected, satispayClosePaymentRequestCaptor.value)
  }

  @Test
  fun `ClosePaymentRequestV2Dto for close payment KO for cancelled transaction is correct for Satispay method`() =
    runTest {
      val transactionOutcome = ClosePaymentOutcome.KO
      val paypalTransactionGatewayAuthorizationRequestedData =
        NpgTransactionGatewayAuthorizationRequestedData(
          LOGO_URI,
          NpgClient.PaymentMethod.SATISPAY.toString(),
          "npgSessionId",
          "npgConfirmPaymentSessionId",
          null)
      val satispayTransactionGatewayAuthorizationData =
        npgTransactionGatewayAuthorizationData(OperationResultDto.DECLINED)

      val activatedEvent = transactionActivateEvent()
      val authEvent =
        TransactionAuthorizationRequestedEvent(
          TRANSACTION_ID,
          TransactionAuthorizationRequestData(
            100,
            10,
            "paymentInstrumentId",
            "pspId",
            PaymentCode.SATY.name,
            "brokerName",
            "pspChannelCode",
            "paymentMethodName",
            "pspBusinessName",
            false,
            AUTHORIZATION_REQUEST_ID,
            TransactionAuthorizationRequestData.PaymentGateway.NPG,
            "paymentMethodDescription",
            paypalTransactionGatewayAuthorizationRequestedData))
      val authCompletedEvent =
        transactionAuthorizationCompletedEvent(satispayTransactionGatewayAuthorizationData)
      val closureRequestedEvent = transactionClosureRequestedEvent()
      val closureError = transactionClosureErrorEvent()
      val transactionId = activatedEvent.transactionId
      val events =
        listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
          as List<TransactionEvent<Any>>

      val closePaymentResponse =
        ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }

      /* preconditions */
      given(
          transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(
            TRANSACTION_ID))
        .willReturn(events.toFlux())

      given(nodeClient.closePayment(capture(satispayClosePaymentRequestCaptor)))
        .willReturn(Mono.just(closePaymentResponse))

      val fee = authEvent.data.fee
      val amount = authEvent.data.amount
      val totalAmount = amount + fee

      val feeEuroCents = BigDecimal(fee)
      val amountEuroCents = BigDecimal(amount)
      val totalAmountEuroCents = BigDecimal(totalAmount)

      /* test */
      assertEquals(
        closePaymentResponse,
        nodeService.closePayment(TransactionId(transactionId), transactionOutcome))

      val expected =
        SatispayClosePaymentRequestV2Dto().apply {
          outcome = SatispayClosePaymentRequestV2Dto.OutcomeEnum.KO
          this.transactionId = transactionId
          paymentTokens =
            activatedEvent.data.paymentNotices.map { paymentNotice -> paymentNotice.paymentToken }
          transactionDetails =
            TransactionDetailsDto().apply {
              transaction =
                TransactionDto().apply {
                  transactionStatus =
                    TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_DENIED.status
                  this.transactionId = transactionId
                  this.fee = feeEuroCents
                  this.amount = amountEuroCents
                  grandTotal = totalAmountEuroCents
                  this.errorCode =
                    (authCompletedEvent.data.transactionGatewayAuthorizationData
                        as NpgTransactionGatewayAuthorizationData)
                      .errorCode
                  rrn = authCompletedEvent.data.rrn
                  creationDate = ZonedDateTime.parse(activatedEvent.creationDate).toOffsetDateTime()
                  psp =
                    PspDto().apply {
                      idPsp = authEvent.data.pspId
                      brokerName = authEvent.data.brokerName
                      idChannel = authEvent.data.pspChannelCode
                      businessName = authEvent.data.pspBusinessName
                      pspOnUs = authEvent.data.isPspOnUs
                    }
                  authorizationCode = null
                  timestampOperation = authCompletedEvent.data.timestampOperation
                  paymentGateway = authEvent.data.paymentGateway.name
                }
              user = UserDto().apply { type = UserDto.TypeEnum.GUEST }
              info =
                InfoDto().apply {
                  type = authEvent.data.paymentTypeCode
                  clientId = Transaction.ClientId.CHECKOUT.name
                  brand = authEvent.data.paymentTypeCode
                  brandLogo =
                    (authEvent.data.transactionGatewayAuthorizationRequestedData
                        as NpgTransactionGatewayAuthorizationRequestedData)
                      .logo
                      .toString()
                  paymentMethodName = authEvent.data.paymentMethodName
                }
            }
          additionalPaymentInformations = null
        }

      assertEquals(expected, satispayClosePaymentRequestCaptor.value)
    }

  @Test
  fun `ClosePaymentRequestV2Dto for close payment KO for authorization KO is correct for Satispay method`() =
    runTest {
      val transactionOutcome = ClosePaymentOutcome.KO
      val paypalTransactionGatewayAuthorizationRequestedData =
        NpgTransactionGatewayAuthorizationRequestedData(
          LOGO_URI,
          NpgClient.PaymentMethod.SATISPAY.toString(),
          "npgSessionId",
          "npgConfirmPaymentSessionId",
          null)
      val satispayTransactionGatewayAuthorizationData =
        npgTransactionGatewayAuthorizationData(OperationResultDto.DECLINED)

      val activatedEvent = transactionActivateEvent()
      val authEvent =
        TransactionAuthorizationRequestedEvent(
          TRANSACTION_ID,
          TransactionAuthorizationRequestData(
            100,
            10,
            "paymentInstrumentId",
            "pspId",
            PaymentCode.SATY.name,
            "brokerName",
            "pspChannelCode",
            "paymentMethodName",
            "pspBusinessName",
            false,
            AUTHORIZATION_REQUEST_ID,
            TransactionAuthorizationRequestData.PaymentGateway.NPG,
            "paymentMethodDescription",
            paypalTransactionGatewayAuthorizationRequestedData))
      val authCompletedEvent =
        transactionAuthorizationCompletedEvent(satispayTransactionGatewayAuthorizationData)
      val closureRequestedEvent = transactionClosureRequestedEvent()
      val closureError = transactionClosureErrorEvent()
      val transactionId = activatedEvent.transactionId
      val events =
        listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
          as List<TransactionEvent<Any>>

      val closePaymentResponse =
        ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }

      /* preconditions */
      given(
          transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(
            TRANSACTION_ID))
        .willReturn(events.toFlux())

      given(nodeClient.closePayment(capture(satispayClosePaymentRequestCaptor)))
        .willReturn(Mono.just(closePaymentResponse))

      val fee = authEvent.data.fee
      val amount = authEvent.data.amount
      val totalAmount = amount + fee

      val feeEuroCents = BigDecimal(fee)
      val amountEuroCents = BigDecimal(amount)
      val totalAmountEuroCents = BigDecimal(totalAmount)

      /* test */
      assertEquals(
        closePaymentResponse,
        nodeService.closePayment(TransactionId(transactionId), transactionOutcome))

      val expected =
        SatispayClosePaymentRequestV2Dto().apply {
          outcome = SatispayClosePaymentRequestV2Dto.OutcomeEnum.KO
          this.transactionId = transactionId
          paymentTokens =
            activatedEvent.data.paymentNotices.map { paymentNotice -> paymentNotice.paymentToken }
          transactionDetails =
            TransactionDetailsDto().apply {
              transaction =
                TransactionDto().apply {
                  transactionStatus =
                    TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_DENIED.status
                  this.transactionId = transactionId
                  this.fee = feeEuroCents
                  this.amount = amountEuroCents
                  grandTotal = totalAmountEuroCents
                  this.errorCode =
                    (authCompletedEvent.data.transactionGatewayAuthorizationData
                        as NpgTransactionGatewayAuthorizationData)
                      .errorCode
                  rrn = authCompletedEvent.data.rrn
                  creationDate = ZonedDateTime.parse(activatedEvent.creationDate).toOffsetDateTime()
                  psp =
                    PspDto().apply {
                      idPsp = authEvent.data.pspId
                      brokerName = authEvent.data.brokerName
                      idChannel = authEvent.data.pspChannelCode
                      businessName = authEvent.data.pspBusinessName
                      pspOnUs = authEvent.data.isPspOnUs
                    }
                  authorizationCode = null
                  timestampOperation = authCompletedEvent.data.timestampOperation
                  paymentGateway = authEvent.data.paymentGateway.name
                }
              user = UserDto().apply { type = UserDto.TypeEnum.GUEST }
              info =
                InfoDto().apply {
                  type = authEvent.data.paymentTypeCode
                  clientId = Transaction.ClientId.CHECKOUT.name
                  brand = authEvent.data.paymentTypeCode
                  brandLogo =
                    (authEvent.data.transactionGatewayAuthorizationRequestedData
                        as NpgTransactionGatewayAuthorizationRequestedData)
                      .logo
                      .toString()
                  paymentMethodName = authEvent.data.paymentMethodName
                }
            }
          additionalPaymentInformations = null
        }

      assertEquals(expected, satispayClosePaymentRequestCaptor.value)
    }

  @ParameterizedTest
  @MethodSource("closePaymentDateFormat")
  fun `ClosePaymentRequestV2Dto for close payment OK has additional properties and transaction details valued correctly for Applepay method`(
    timestampOperation: String,
    expectedLocalDate: String
  ) = runTest {
    val transactionOutcome = ClosePaymentOutcome.OK
    val satispayTransactionGatewayAuthorizationRequestedData =
      NpgTransactionGatewayAuthorizationRequestedData(
        LOGO_URI,
        NpgClient.PaymentMethod.APPLEPAY.toString(),
        "npgSessionId",
        "npgConfirmPaymentSessionId",
        null)
    val applepayTransactionGatewayAuthorizationData =
      npgTransactionGatewayAuthorizationData(OperationResultDto.EXECUTED)

    val activatedEvent = transactionActivateEvent()
    val authEvent =
      TransactionAuthorizationRequestedEvent(
        TRANSACTION_ID,
        TransactionAuthorizationRequestData(
          100,
          10,
          "paymentInstrumentId",
          "pspId",
          PaymentCode.APPL.name,
          "brokerName",
          "pspChannelCode",
          "paymentMethodName",
          "pspBusinessName",
          false,
          AUTHORIZATION_REQUEST_ID,
          TransactionAuthorizationRequestData.PaymentGateway.NPG,
          "paymentMethodDescription",
          satispayTransactionGatewayAuthorizationRequestedData))
    val authCompletedEvent =
      transactionAuthorizationCompletedEvent(applepayTransactionGatewayAuthorizationData)
    val closureRequestedEvent = transactionClosureRequestedEvent()
    val closureError = transactionClosureErrorEvent()
    val transactionId = activatedEvent.transactionId
    val nodoTimestampOperation = OffsetDateTime.parse(timestampOperation)
    authCompletedEvent.data.timestampOperation = nodoTimestampOperation.toString()
    val events =
      listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
        as List<TransactionEvent<Any>>

    val closePaymentResponse =
      ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }

    /* preconditions */
    given(
        transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(TRANSACTION_ID))
      .willReturn(events.toFlux())
    given(confidentialDataUtils.eCommerceDecrypt(eq(activatedEvent.data.email), any()))
      .willReturn(Mono.just(Email(EMAIL_STRING)))

    given(nodeClient.closePayment(capture(satispayClosePaymentRequestCaptor)))
      .willReturn(Mono.just(closePaymentResponse))

    val fee = authEvent.data.fee
    val amount = authEvent.data.amount
    val totalAmount = amount + fee

    val feeEuro = EuroUtils.euroCentsToEuro(fee)
    val totalAmountEuro = EuroUtils.euroCentsToEuro(totalAmount)

    val feeEuroCents = BigDecimal(fee)
    val amountEuroCents = BigDecimal(amount)
    val totalAmountEuroCents = BigDecimal(totalAmount)

    /* test */
    assertEquals(
      closePaymentResponse,
      nodeService.closePayment(TransactionId(transactionId), transactionOutcome))
    val expectedTimestamp = expectedLocalDate

    val expected =
      ApplePayClosePaymentRequestV2Dto().apply {
        outcome = ApplePayClosePaymentRequestV2Dto.OutcomeEnum.OK
        this.transactionId = transactionId
        paymentTokens =
          activatedEvent.data.paymentNotices.map { paymentNotice -> paymentNotice.paymentToken }
        this.timestampOperation = OffsetDateTime.parse(authCompletedEvent.data.timestampOperation)
        this.fee = feeEuro
        idPSP = authEvent.data.pspId
        idChannel = authEvent.data.pspChannelCode
        idBrokerPSP = authEvent.data.brokerName
        paymentMethod = authEvent.data.paymentTypeCode
        this.totalAmount = totalAmountEuro
        transactionDetails =
          TransactionDetailsDto().apply {
            transaction =
              TransactionDto().apply {
                transactionStatus =
                  TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_CONFIRMED.status
                this.transactionId = transactionId
                this.fee = feeEuroCents
                this.amount = amountEuroCents
                grandTotal = totalAmountEuroCents
                this.errorCode = errorCode
                rrn = authCompletedEvent.data.rrn
                creationDate = ZonedDateTime.parse(activatedEvent.creationDate).toOffsetDateTime()
                psp =
                  PspDto().apply {
                    idPsp = authEvent.data.pspId
                    brokerName = authEvent.data.brokerName
                    idChannel = authEvent.data.pspChannelCode
                    businessName = authEvent.data.pspBusinessName
                    pspOnUs = authEvent.data.isPspOnUs
                  }
                authorizationCode = authCompletedEvent.data.authorizationCode
                this.timestampOperation = authCompletedEvent.data.timestampOperation
                paymentGateway = authEvent.data.paymentGateway.name
              }
            user = UserDto().apply { type = UserDto.TypeEnum.GUEST }
            info =
              InfoDto().apply {
                type = authEvent.data.paymentTypeCode
                clientId = Transaction.ClientId.CHECKOUT.name
                brand = authEvent.data.paymentTypeCode
                brandLogo =
                  (authEvent.data.transactionGatewayAuthorizationRequestedData
                      as NpgTransactionGatewayAuthorizationRequestedData)
                    .logo
                    .toString()
                paymentMethodName = authEvent.data.paymentMethodName
              }
          }
        additionalPaymentInformations =
          ApplePayAdditionalPaymentInformationsDto().apply {
            this.rrn = authCompletedEvent.data.rrn
            this.timestampOperation = expectedTimestamp
            this.fee = feeEuro.toString()
            this.totalAmount = totalAmountEuro.toString()
            this.email = EMAIL_STRING
            this.authorizationCode = authCompletedEvent.data.authorizationCode
          }
      }

    assertEquals(expected, satispayClosePaymentRequestCaptor.value)
  }

  @Test
  fun `ClosePaymentRequestV2Dto for close payment KO for cancelled transaction is correct for Applepay method`() =
    runTest {
      val transactionOutcome = ClosePaymentOutcome.KO
      val paypalTransactionGatewayAuthorizationRequestedData =
        NpgTransactionGatewayAuthorizationRequestedData(
          LOGO_URI,
          NpgClient.PaymentMethod.APPLEPAY.toString(),
          "npgSessionId",
          "npgConfirmPaymentSessionId",
          null)
      val satispayTransactionGatewayAuthorizationData =
        npgTransactionGatewayAuthorizationData(OperationResultDto.DECLINED)

      val activatedEvent = transactionActivateEvent()
      val authEvent =
        TransactionAuthorizationRequestedEvent(
          TRANSACTION_ID,
          TransactionAuthorizationRequestData(
            100,
            10,
            "paymentInstrumentId",
            "pspId",
            PaymentCode.APPL.name,
            "brokerName",
            "pspChannelCode",
            "paymentMethodName",
            "pspBusinessName",
            false,
            AUTHORIZATION_REQUEST_ID,
            TransactionAuthorizationRequestData.PaymentGateway.NPG,
            "paymentMethodDescription",
            paypalTransactionGatewayAuthorizationRequestedData))
      val authCompletedEvent =
        transactionAuthorizationCompletedEvent(satispayTransactionGatewayAuthorizationData)
      val closureRequestedEvent = transactionClosureRequestedEvent()
      val closureError = transactionClosureErrorEvent()
      val transactionId = activatedEvent.transactionId
      val events =
        listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
          as List<TransactionEvent<Any>>

      val closePaymentResponse =
        ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }

      /* preconditions */
      given(
          transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(
            TRANSACTION_ID))
        .willReturn(events.toFlux())

      given(nodeClient.closePayment(capture(applepayClosePaymentRequestCaptor)))
        .willReturn(Mono.just(closePaymentResponse))

      val fee = authEvent.data.fee
      val amount = authEvent.data.amount
      val totalAmount = amount + fee

      val feeEuroCents = BigDecimal(fee)
      val amountEuroCents = BigDecimal(amount)
      val totalAmountEuroCents = BigDecimal(totalAmount)

      /* test */
      assertEquals(
        closePaymentResponse,
        nodeService.closePayment(TransactionId(transactionId), transactionOutcome))

      val expected =
        ApplePayClosePaymentRequestV2Dto().apply {
          outcome = ApplePayClosePaymentRequestV2Dto.OutcomeEnum.KO
          this.transactionId = transactionId
          paymentTokens =
            activatedEvent.data.paymentNotices.map { paymentNotice -> paymentNotice.paymentToken }
          transactionDetails =
            TransactionDetailsDto().apply {
              transaction =
                TransactionDto().apply {
                  transactionStatus =
                    TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_DENIED.status
                  this.transactionId = transactionId
                  this.fee = feeEuroCents
                  this.amount = amountEuroCents
                  grandTotal = totalAmountEuroCents
                  this.errorCode =
                    (authCompletedEvent.data.transactionGatewayAuthorizationData
                        as NpgTransactionGatewayAuthorizationData)
                      .errorCode
                  rrn = authCompletedEvent.data.rrn
                  creationDate = ZonedDateTime.parse(activatedEvent.creationDate).toOffsetDateTime()
                  psp =
                    PspDto().apply {
                      idPsp = authEvent.data.pspId
                      brokerName = authEvent.data.brokerName
                      idChannel = authEvent.data.pspChannelCode
                      businessName = authEvent.data.pspBusinessName
                      pspOnUs = authEvent.data.isPspOnUs
                    }
                  authorizationCode = null
                  timestampOperation = authCompletedEvent.data.timestampOperation
                  paymentGateway = authEvent.data.paymentGateway.name
                }
              user = UserDto().apply { type = UserDto.TypeEnum.GUEST }
              info =
                InfoDto().apply {
                  type = authEvent.data.paymentTypeCode
                  clientId = Transaction.ClientId.CHECKOUT.name
                  brand = authEvent.data.paymentTypeCode
                  brandLogo =
                    (authEvent.data.transactionGatewayAuthorizationRequestedData
                        as NpgTransactionGatewayAuthorizationRequestedData)
                      .logo
                      .toString()
                  paymentMethodName = authEvent.data.paymentMethodName
                }
            }
          additionalPaymentInformations = null
        }

      assertEquals(expected, applepayClosePaymentRequestCaptor.value)
    }

  @Test
  fun `ClosePaymentRequestV2Dto for close payment KO for authorization KO is correct for Applepay method`() =
    runTest {
      val transactionOutcome = ClosePaymentOutcome.KO
      val paypalTransactionGatewayAuthorizationRequestedData =
        NpgTransactionGatewayAuthorizationRequestedData(
          LOGO_URI,
          NpgClient.PaymentMethod.APPLEPAY.toString(),
          "npgSessionId",
          "npgConfirmPaymentSessionId",
          null)
      val satispayTransactionGatewayAuthorizationData =
        npgTransactionGatewayAuthorizationData(OperationResultDto.DECLINED)

      val activatedEvent = transactionActivateEvent()
      val authEvent =
        TransactionAuthorizationRequestedEvent(
          TRANSACTION_ID,
          TransactionAuthorizationRequestData(
            100,
            10,
            "paymentInstrumentId",
            "pspId",
            PaymentCode.APPL.name,
            "brokerName",
            "pspChannelCode",
            "paymentMethodName",
            "pspBusinessName",
            false,
            AUTHORIZATION_REQUEST_ID,
            TransactionAuthorizationRequestData.PaymentGateway.NPG,
            "paymentMethodDescription",
            paypalTransactionGatewayAuthorizationRequestedData))
      val authCompletedEvent =
        transactionAuthorizationCompletedEvent(satispayTransactionGatewayAuthorizationData)
      val closureRequestedEvent = transactionClosureRequestedEvent()
      val closureError = transactionClosureErrorEvent()
      val transactionId = activatedEvent.transactionId
      val events =
        listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
          as List<TransactionEvent<Any>>

      val closePaymentResponse =
        ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }

      /* preconditions */
      given(
          transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(
            TRANSACTION_ID))
        .willReturn(events.toFlux())

      given(nodeClient.closePayment(capture(applepayClosePaymentRequestCaptor)))
        .willReturn(Mono.just(closePaymentResponse))

      val fee = authEvent.data.fee
      val amount = authEvent.data.amount
      val totalAmount = amount + fee

      val feeEuroCents = BigDecimal(fee)
      val amountEuroCents = BigDecimal(amount)
      val totalAmountEuroCents = BigDecimal(totalAmount)

      /* test */
      assertEquals(
        closePaymentResponse,
        nodeService.closePayment(TransactionId(transactionId), transactionOutcome))

      val expected =
        ApplePayClosePaymentRequestV2Dto().apply {
          outcome = ApplePayClosePaymentRequestV2Dto.OutcomeEnum.KO
          this.transactionId = transactionId
          paymentTokens =
            activatedEvent.data.paymentNotices.map { paymentNotice -> paymentNotice.paymentToken }
          transactionDetails =
            TransactionDetailsDto().apply {
              transaction =
                TransactionDto().apply {
                  transactionStatus =
                    TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_DENIED.status
                  this.transactionId = transactionId
                  this.fee = feeEuroCents
                  this.amount = amountEuroCents
                  grandTotal = totalAmountEuroCents
                  this.errorCode =
                    (authCompletedEvent.data.transactionGatewayAuthorizationData
                        as NpgTransactionGatewayAuthorizationData)
                      .errorCode
                  rrn = authCompletedEvent.data.rrn
                  creationDate = ZonedDateTime.parse(activatedEvent.creationDate).toOffsetDateTime()
                  psp =
                    PspDto().apply {
                      idPsp = authEvent.data.pspId
                      brokerName = authEvent.data.brokerName
                      idChannel = authEvent.data.pspChannelCode
                      businessName = authEvent.data.pspBusinessName
                      pspOnUs = authEvent.data.isPspOnUs
                    }
                  authorizationCode = null
                  timestampOperation = authCompletedEvent.data.timestampOperation
                  paymentGateway = authEvent.data.paymentGateway.name
                }
              user = UserDto().apply { type = UserDto.TypeEnum.GUEST }
              info =
                InfoDto().apply {
                  type = authEvent.data.paymentTypeCode
                  clientId = Transaction.ClientId.CHECKOUT.name
                  brand = authEvent.data.paymentTypeCode
                  brandLogo =
                    (authEvent.data.transactionGatewayAuthorizationRequestedData
                        as NpgTransactionGatewayAuthorizationRequestedData)
                      .logo
                      .toString()
                  paymentMethodName = authEvent.data.paymentMethodName
                }
            }
          additionalPaymentInformations = null
        }

      assertEquals(expected, applepayClosePaymentRequestCaptor.value)
    }

  @ParameterizedTest
  @MethodSource("closePaymentDateFormat")
  fun `ClosePaymentRequestV2Dto for close payment OK has additional properties and transaction details valued correctly for PayPal method`(
    timestampOperation: String,
    expectedLocalDate: String
  ) = runTest {
    val transactionOutcome = ClosePaymentOutcome.OK
    val paypalTransactionGatewayAuthorizationRequestedData =
      NpgTransactionGatewayAuthorizationRequestedData(
        LOGO_URI,
        NpgClient.PaymentMethod.PAYPAL.toString(),
        "npgSessionId",
        "npgConfirmPaymentSessionId",
        null)
    val paypalTransactionGatewayAuthorizationData =
      npgTransactionGatewayAuthorizationData(OperationResultDto.EXECUTED)

    val activatedEvent = transactionActivateEvent()
    val authEvent =
      TransactionAuthorizationRequestedEvent(
        TRANSACTION_ID,
        TransactionAuthorizationRequestData(
          100,
          10,
          "paymentInstrumentId",
          "pspId",
          PaymentCode.PPAL.name,
          "brokerName",
          "pspChannelCode",
          "paymentMethodName",
          "pspBusinessName",
          false,
          AUTHORIZATION_REQUEST_ID,
          TransactionAuthorizationRequestData.PaymentGateway.NPG,
          "paymentMethodDescription",
          paypalTransactionGatewayAuthorizationRequestedData))
    val authCompletedEvent =
      transactionAuthorizationCompletedEvent(paypalTransactionGatewayAuthorizationData)
    val closureRequestedEvent = transactionClosureRequestedEvent()
    val closureError = transactionClosureErrorEvent()
    val transactionId = activatedEvent.transactionId
    val nodoTimestampOperation = OffsetDateTime.parse(timestampOperation)
    authCompletedEvent.data.timestampOperation = nodoTimestampOperation.toString()
    val events =
      listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
        as List<TransactionEvent<Any>>

    val closePaymentResponse =
      ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }

    /* preconditions */
    given(
        transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(TRANSACTION_ID))
      .willReturn(events.toFlux())
    given(confidentialDataUtils.eCommerceDecrypt(eq(activatedEvent.data.email), any()))
      .willReturn(Mono.just(Email(EMAIL_STRING)))

    given(nodeClient.closePayment(capture(paypalClosePaymentRequestCaptor)))
      .willReturn(Mono.just(closePaymentResponse))

    val fee = authEvent.data.fee
    val amount = authEvent.data.amount
    val totalAmount = amount + fee

    val feeEuro = EuroUtils.euroCentsToEuro(fee)
    val totalAmountEuro = EuroUtils.euroCentsToEuro(totalAmount)

    val feeEuroCents = BigDecimal(fee)
    val amountEuroCents = BigDecimal(amount)
    val totalAmountEuroCents = BigDecimal(totalAmount)

    /* test */
    assertEquals(
      closePaymentResponse,
      nodeService.closePayment(TransactionId(transactionId), transactionOutcome))
    val expectedTimestamp = expectedLocalDate

    val expected =
      PayPalClosePaymentRequestV2Dto().apply {
        outcome = PayPalClosePaymentRequestV2Dto.OutcomeEnum.OK
        this.transactionId = transactionId
        paymentTokens =
          activatedEvent.data.paymentNotices.map { paymentNotice -> paymentNotice.paymentToken }
        this.timestampOperation = OffsetDateTime.parse(authCompletedEvent.data.timestampOperation)
        this.fee = feeEuro
        idPSP = authEvent.data.pspId
        idChannel = authEvent.data.pspChannelCode
        idBrokerPSP = authEvent.data.brokerName
        paymentMethod = authEvent.data.paymentTypeCode
        this.totalAmount = totalAmountEuro
        transactionDetails =
          TransactionDetailsDto().apply {
            transaction =
              TransactionDto().apply {
                transactionStatus =
                  TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_CONFIRMED.status
                this.transactionId = transactionId
                this.fee = feeEuroCents
                this.amount = amountEuroCents
                grandTotal = totalAmountEuroCents
                this.errorCode = errorCode
                rrn = authCompletedEvent.data.rrn
                creationDate = ZonedDateTime.parse(activatedEvent.creationDate).toOffsetDateTime()
                psp =
                  PspDto().apply {
                    idPsp = authEvent.data.pspId
                    brokerName = authEvent.data.brokerName
                    idChannel = authEvent.data.pspChannelCode
                    businessName = authEvent.data.pspBusinessName
                    pspOnUs = authEvent.data.isPspOnUs
                  }
                authorizationCode = authCompletedEvent.data.authorizationCode
                this.timestampOperation = authCompletedEvent.data.timestampOperation
                paymentGateway = authEvent.data.paymentGateway.name
              }
            user = UserDto().apply { type = UserDto.TypeEnum.GUEST }
            info =
              InfoDto().apply {
                type = authEvent.data.paymentTypeCode
                clientId = Transaction.ClientId.CHECKOUT.name
                brand = authEvent.data.paymentTypeCode
                brandLogo =
                  (authEvent.data.transactionGatewayAuthorizationRequestedData
                      as NpgTransactionGatewayAuthorizationRequestedData)
                    .logo
                    .toString()
                paymentMethodName = authEvent.data.paymentMethodName
              }
          }
        additionalPaymentInformations =
          PayPalAdditionalPaymentInformationsDto().apply {
            this.transactionId =
              (authCompletedEvent.data.transactionGatewayAuthorizationData
                  as NpgTransactionGatewayAuthorizationData)
                .operationId
            this.pspTransactionId =
              (authCompletedEvent.data.transactionGatewayAuthorizationData
                  as NpgTransactionGatewayAuthorizationData)
                .paymentEndToEndId
            this.timestampOperation = expectedTimestamp
            this.fee = feeEuro.toString()
            this.totalAmount = totalAmountEuro.toString()
            this.email = EMAIL_STRING
          }
      }

    assertEquals(expected, paypalClosePaymentRequestCaptor.value)
  }

  @Test
  fun `ClosePaymentRequestV2Dto for close payment KO for cancelled transaction is correct for PayPal method`() =
    runTest {
      val transactionOutcome = ClosePaymentOutcome.KO
      val paypalTransactionGatewayAuthorizationRequestedData =
        NpgTransactionGatewayAuthorizationRequestedData(
          LOGO_URI,
          NpgClient.PaymentMethod.PAYPAL.toString(),
          "npgSessionId",
          "npgConfirmPaymentSessionId",
          null)
      val paypalTransactionGatewayAuthorizationData =
        npgTransactionGatewayAuthorizationData(OperationResultDto.DECLINED)

      val activatedEvent = transactionActivateEvent()
      val authEvent =
        TransactionAuthorizationRequestedEvent(
          TRANSACTION_ID,
          TransactionAuthorizationRequestData(
            100,
            10,
            "paymentInstrumentId",
            "pspId",
            PaymentCode.PPAL.name,
            "brokerName",
            "pspChannelCode",
            "paymentMethodName",
            "pspBusinessName",
            false,
            AUTHORIZATION_REQUEST_ID,
            TransactionAuthorizationRequestData.PaymentGateway.NPG,
            "paymentMethodDescription",
            paypalTransactionGatewayAuthorizationRequestedData))
      val authCompletedEvent =
        transactionAuthorizationCompletedEvent(paypalTransactionGatewayAuthorizationData)
      val closureRequestedEvent = transactionClosureRequestedEvent()
      val closureError = transactionClosureErrorEvent()
      val transactionId = activatedEvent.transactionId
      val events =
        listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
          as List<TransactionEvent<Any>>

      val closePaymentResponse =
        ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }

      /* preconditions */
      given(
          transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(
            TRANSACTION_ID))
        .willReturn(events.toFlux())

      given(nodeClient.closePayment(capture(paypalClosePaymentRequestCaptor)))
        .willReturn(Mono.just(closePaymentResponse))

      val fee = authEvent.data.fee
      val amount = authEvent.data.amount
      val totalAmount = amount + fee

      val feeEuroCents = BigDecimal(fee)
      val amountEuroCents = BigDecimal(amount)
      val totalAmountEuroCents = BigDecimal(totalAmount)

      /* test */
      assertEquals(
        closePaymentResponse,
        nodeService.closePayment(TransactionId(transactionId), transactionOutcome))

      val expected =
        PayPalClosePaymentRequestV2Dto().apply {
          outcome = PayPalClosePaymentRequestV2Dto.OutcomeEnum.KO
          this.transactionId = transactionId
          paymentTokens =
            activatedEvent.data.paymentNotices.map { paymentNotice -> paymentNotice.paymentToken }
          transactionDetails =
            TransactionDetailsDto().apply {
              transaction =
                TransactionDto().apply {
                  transactionStatus =
                    TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_DENIED.status
                  this.transactionId = transactionId
                  this.fee = feeEuroCents
                  this.amount = amountEuroCents
                  grandTotal = totalAmountEuroCents
                  this.errorCode =
                    (authCompletedEvent.data.transactionGatewayAuthorizationData
                        as NpgTransactionGatewayAuthorizationData)
                      .errorCode
                  rrn = authCompletedEvent.data.rrn
                  creationDate = ZonedDateTime.parse(activatedEvent.creationDate).toOffsetDateTime()
                  psp =
                    PspDto().apply {
                      idPsp = authEvent.data.pspId
                      brokerName = authEvent.data.brokerName
                      idChannel = authEvent.data.pspChannelCode
                      businessName = authEvent.data.pspBusinessName
                      pspOnUs = authEvent.data.isPspOnUs
                    }
                  authorizationCode = null
                  timestampOperation = authCompletedEvent.data.timestampOperation
                  paymentGateway = authEvent.data.paymentGateway.name
                }
              user = UserDto().apply { type = UserDto.TypeEnum.GUEST }
              info =
                InfoDto().apply {
                  type = authEvent.data.paymentTypeCode
                  clientId = Transaction.ClientId.CHECKOUT.name
                  brand = authEvent.data.paymentTypeCode
                  brandLogo =
                    (authEvent.data.transactionGatewayAuthorizationRequestedData
                        as NpgTransactionGatewayAuthorizationRequestedData)
                      .logo
                      .toString()
                  paymentMethodName = authEvent.data.paymentMethodName
                }
            }
          additionalPaymentInformations = null
        }

      assertEquals(expected, paypalClosePaymentRequestCaptor.value)
    }

  @Test
  fun `ClosePaymentRequestV2Dto for close payment KO for authorization KO is correct for PayPal method`() =
    runTest {
      val transactionOutcome = ClosePaymentOutcome.KO
      val paypalTransactionGatewayAuthorizationRequestedData =
        NpgTransactionGatewayAuthorizationRequestedData(
          LOGO_URI,
          NpgClient.PaymentMethod.PAYPAL.toString(),
          "npgSessionId",
          "npgConfirmPaymentSessionId",
          null)
      val paypalTransactionGatewayAuthorizationData =
        npgTransactionGatewayAuthorizationData(OperationResultDto.DECLINED)

      val activatedEvent = transactionActivateEvent()
      val authEvent =
        TransactionAuthorizationRequestedEvent(
          TRANSACTION_ID,
          TransactionAuthorizationRequestData(
            100,
            10,
            "paymentInstrumentId",
            "pspId",
            PaymentCode.PPAL.name,
            "brokerName",
            "pspChannelCode",
            "paymentMethodName",
            "pspBusinessName",
            false,
            AUTHORIZATION_REQUEST_ID,
            TransactionAuthorizationRequestData.PaymentGateway.NPG,
            "paymentMethodDescription",
            paypalTransactionGatewayAuthorizationRequestedData))
      val authCompletedEvent =
        transactionAuthorizationCompletedEvent(paypalTransactionGatewayAuthorizationData)
      val closureRequestedEvent = transactionClosureRequestedEvent()
      val closureError = transactionClosureErrorEvent()
      val transactionId = activatedEvent.transactionId
      val events =
        listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
          as List<TransactionEvent<Any>>

      val closePaymentResponse =
        ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }

      /* preconditions */
      given(
          transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(
            TRANSACTION_ID))
        .willReturn(events.toFlux())

      given(nodeClient.closePayment(capture(paypalClosePaymentRequestCaptor)))
        .willReturn(Mono.just(closePaymentResponse))

      val fee = authEvent.data.fee
      val amount = authEvent.data.amount
      val totalAmount = amount + fee

      val feeEuroCents = BigDecimal(fee)
      val amountEuroCents = BigDecimal(amount)
      val totalAmountEuroCents = BigDecimal(totalAmount)

      /* test */
      assertEquals(
        closePaymentResponse,
        nodeService.closePayment(TransactionId(transactionId), transactionOutcome))

      val expected =
        PayPalClosePaymentRequestV2Dto().apply {
          outcome = PayPalClosePaymentRequestV2Dto.OutcomeEnum.KO
          this.transactionId = transactionId
          paymentTokens =
            activatedEvent.data.paymentNotices.map { paymentNotice -> paymentNotice.paymentToken }
          transactionDetails =
            TransactionDetailsDto().apply {
              transaction =
                TransactionDto().apply {
                  transactionStatus =
                    TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_DENIED.status
                  this.transactionId = transactionId
                  this.fee = feeEuroCents
                  this.amount = amountEuroCents
                  grandTotal = totalAmountEuroCents
                  this.errorCode =
                    (authCompletedEvent.data.transactionGatewayAuthorizationData
                        as NpgTransactionGatewayAuthorizationData)
                      .errorCode
                  rrn = authCompletedEvent.data.rrn
                  creationDate = ZonedDateTime.parse(activatedEvent.creationDate).toOffsetDateTime()
                  psp =
                    PspDto().apply {
                      idPsp = authEvent.data.pspId
                      brokerName = authEvent.data.brokerName
                      idChannel = authEvent.data.pspChannelCode
                      businessName = authEvent.data.pspBusinessName
                      pspOnUs = authEvent.data.isPspOnUs
                    }
                  authorizationCode = null
                  timestampOperation = authCompletedEvent.data.timestampOperation
                  paymentGateway = authEvent.data.paymentGateway.name
                }
              user = UserDto().apply { type = UserDto.TypeEnum.GUEST }
              info =
                InfoDto().apply {
                  type = authEvent.data.paymentTypeCode
                  clientId = Transaction.ClientId.CHECKOUT.name
                  brand = authEvent.data.paymentTypeCode
                  brandLogo =
                    (authEvent.data.transactionGatewayAuthorizationRequestedData
                        as NpgTransactionGatewayAuthorizationRequestedData)
                      .logo
                      .toString()
                  paymentMethodName = authEvent.data.paymentMethodName
                }
            }
          additionalPaymentInformations = null
        }

      assertEquals(expected, paypalClosePaymentRequestCaptor.value)
    }

  @ParameterizedTest
  @MethodSource("closePaymentDateFormat")
  fun `ClosePaymentRequestV2Dto for close payment OK has additional properties and transaction details valued correctly for BancomatPay method`(
    timestampOperation: String,
    expectedLocalDate: String
  ) = runTest {
    val transactionOutcome = ClosePaymentOutcome.OK
    val bancomatPayTransactionGatewayAuthorizationRequestedData =
      NpgTransactionGatewayAuthorizationRequestedData(
        LOGO_URI,
        NpgClient.PaymentMethod.BANCOMATPAY.toString(),
        "npgSessionId",
        "npgConfirmPaymentSessionId",
        null)
    val bancomatPayTransactionGatewayAuthorizationData =
      npgTransactionGatewayAuthorizationData(OperationResultDto.EXECUTED)

    val activatedEvent = transactionActivateEvent()
    val authEvent =
      TransactionAuthorizationRequestedEvent(
        TRANSACTION_ID,
        TransactionAuthorizationRequestData(
          100,
          10,
          "paymentInstrumentId",
          "pspId",
          PaymentCode.BPAY.name,
          "brokerName",
          "pspChannelCode",
          "paymentMethodName",
          "pspBusinessName",
          false,
          AUTHORIZATION_REQUEST_ID,
          TransactionAuthorizationRequestData.PaymentGateway.NPG,
          "paymentMethodDescription",
          bancomatPayTransactionGatewayAuthorizationRequestedData))
    val authCompletedEvent =
      transactionAuthorizationCompletedEvent(bancomatPayTransactionGatewayAuthorizationData)
    val closureRequestedEvent = transactionClosureRequestedEvent()
    val closureError = transactionClosureErrorEvent()
    val transactionId = activatedEvent.transactionId
    val nodoTimestampOperation = OffsetDateTime.parse(timestampOperation)
    authCompletedEvent.data.timestampOperation = nodoTimestampOperation.toString()
    val events =
      listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
        as List<TransactionEvent<Any>>

    val closePaymentResponse =
      ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }

    /* preconditions */
    given(
        transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(TRANSACTION_ID))
      .willReturn(events.toFlux())
    given(confidentialDataUtils.eCommerceDecrypt(eq(activatedEvent.data.email), any()))
      .willReturn(Mono.just(Email(EMAIL_STRING)))

    given(nodeClient.closePayment(capture(bancomatPayClosePaymentRequestCaptor)))
      .willReturn(Mono.just(closePaymentResponse))

    val fee = authEvent.data.fee
    val amount = authEvent.data.amount
    val totalAmount = amount + fee

    val feeEuro = EuroUtils.euroCentsToEuro(fee)
    val totalAmountEuro = EuroUtils.euroCentsToEuro(totalAmount)

    val feeEuroCents = BigDecimal(fee)
    val amountEuroCents = BigDecimal(amount)
    val totalAmountEuroCents = BigDecimal(totalAmount)

    /* test */
    assertEquals(
      closePaymentResponse,
      nodeService.closePayment(TransactionId(transactionId), transactionOutcome))
    val expectedTimestamp = expectedLocalDate

    val expected =
      BancomatPayClosePaymentRequestV2Dto().apply {
        outcome = BancomatPayClosePaymentRequestV2Dto.OutcomeEnum.OK
        this.transactionId = transactionId
        paymentTokens =
          activatedEvent.data.paymentNotices.map { paymentNotice -> paymentNotice.paymentToken }
        this.timestampOperation = OffsetDateTime.parse(authCompletedEvent.data.timestampOperation)
        this.fee = feeEuro
        idPSP = authEvent.data.pspId
        idChannel = authEvent.data.pspChannelCode
        idBrokerPSP = authEvent.data.brokerName
        paymentMethod = authEvent.data.paymentTypeCode
        this.totalAmount = totalAmountEuro
        transactionDetails =
          TransactionDetailsDto().apply {
            transaction =
              TransactionDto().apply {
                transactionStatus =
                  TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_CONFIRMED.status
                this.transactionId = transactionId
                this.fee = feeEuroCents
                this.amount = amountEuroCents
                grandTotal = totalAmountEuroCents
                this.errorCode = errorCode
                rrn = authCompletedEvent.data.rrn
                creationDate = ZonedDateTime.parse(activatedEvent.creationDate).toOffsetDateTime()
                psp =
                  PspDto().apply {
                    idPsp = authEvent.data.pspId
                    brokerName = authEvent.data.brokerName
                    idChannel = authEvent.data.pspChannelCode
                    businessName = authEvent.data.pspBusinessName
                    pspOnUs = authEvent.data.isPspOnUs
                  }
                authorizationCode = authCompletedEvent.data.authorizationCode
                this.timestampOperation = authCompletedEvent.data.timestampOperation
                paymentGateway = authEvent.data.paymentGateway.name
              }
            user = UserDto().apply { type = UserDto.TypeEnum.GUEST }
            info =
              InfoDto().apply {
                type = authEvent.data.paymentTypeCode
                clientId = Transaction.ClientId.CHECKOUT.name
                brand = authEvent.data.paymentTypeCode
                brandLogo =
                  (authEvent.data.transactionGatewayAuthorizationRequestedData
                      as NpgTransactionGatewayAuthorizationRequestedData)
                    .logo
                    .toString()
                paymentMethodName = authEvent.data.paymentMethodName
              }
          }
        additionalPaymentInformations =
          BancomatPayAdditionalPaymentInformationsDto().apply {
            this.transactionId =
              (authCompletedEvent.data.transactionGatewayAuthorizationData
                  as NpgTransactionGatewayAuthorizationData)
                .paymentEndToEndId
            this.outcomePaymentGateway =
              BancomatPayAdditionalPaymentInformationsDto.OutcomePaymentGatewayEnum.OK
            this.totalAmount = totalAmountEuro.toString()
            this.fee = feeEuro.toString()
            this.timestampOperation = expectedTimestamp
            this.authorizationCode =
              (authCompletedEvent.data.transactionGatewayAuthorizationData
                  as NpgTransactionGatewayAuthorizationData)
                .operationId
            this.email = EMAIL_STRING
          }
      }

    assertEquals(expected, bancomatPayClosePaymentRequestCaptor.value)
  }

  @Test
  fun `ClosePaymentRequestV2Dto for close payment KO for cancelled transaction is correct for BancomatPay method`() =
    runTest {
      val transactionOutcome = ClosePaymentOutcome.KO
      val bancomatPayTransactionGatewayAuthorizationRequestedData =
        NpgTransactionGatewayAuthorizationRequestedData(
          LOGO_URI,
          NpgClient.PaymentMethod.BANCOMATPAY.toString(),
          "npgSessionId",
          "npgConfirmPaymentSessionId",
          null)
      val bancomatPayTransactionGatewayAuthorizationData =
        npgTransactionGatewayAuthorizationData(OperationResultDto.DECLINED)

      val activatedEvent = transactionActivateEvent()
      val authEvent =
        TransactionAuthorizationRequestedEvent(
          TRANSACTION_ID,
          TransactionAuthorizationRequestData(
            100,
            10,
            "paymentInstrumentId",
            "pspId",
            PaymentCode.BPAY.name,
            "brokerName",
            "pspChannelCode",
            "paymentMethodName",
            "pspBusinessName",
            false,
            AUTHORIZATION_REQUEST_ID,
            TransactionAuthorizationRequestData.PaymentGateway.NPG,
            "paymentMethodDescription",
            bancomatPayTransactionGatewayAuthorizationRequestedData))
      val authCompletedEvent =
        transactionAuthorizationCompletedEvent(bancomatPayTransactionGatewayAuthorizationData)
      val closureRequestedEvent = transactionClosureRequestedEvent()
      val closureError = transactionClosureErrorEvent()
      val transactionId = activatedEvent.transactionId
      val events =
        listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
          as List<TransactionEvent<Any>>

      val closePaymentResponse =
        ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }

      /* preconditions */
      given(
          transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(
            TRANSACTION_ID))
        .willReturn(events.toFlux())

      given(nodeClient.closePayment(capture(bancomatPayClosePaymentRequestCaptor)))
        .willReturn(Mono.just(closePaymentResponse))

      val fee = authEvent.data.fee
      val amount = authEvent.data.amount
      val totalAmount = amount + fee

      val feeEuroCents = BigDecimal(fee)
      val amountEuroCents = BigDecimal(amount)
      val totalAmountEuroCents = BigDecimal(totalAmount)

      /* test */
      assertEquals(
        closePaymentResponse,
        nodeService.closePayment(TransactionId(transactionId), transactionOutcome))

      val expected =
        BancomatPayClosePaymentRequestV2Dto().apply {
          outcome = BancomatPayClosePaymentRequestV2Dto.OutcomeEnum.KO
          this.transactionId = transactionId
          paymentTokens =
            activatedEvent.data.paymentNotices.map { paymentNotice -> paymentNotice.paymentToken }
          transactionDetails =
            TransactionDetailsDto().apply {
              transaction =
                TransactionDto().apply {
                  transactionStatus =
                    TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_DENIED.status
                  this.transactionId = transactionId
                  this.fee = feeEuroCents
                  this.amount = amountEuroCents
                  grandTotal = totalAmountEuroCents
                  this.errorCode =
                    (authCompletedEvent.data.transactionGatewayAuthorizationData
                        as NpgTransactionGatewayAuthorizationData)
                      .errorCode
                  rrn = authCompletedEvent.data.rrn
                  creationDate = ZonedDateTime.parse(activatedEvent.creationDate).toOffsetDateTime()
                  psp =
                    PspDto().apply {
                      idPsp = authEvent.data.pspId
                      brokerName = authEvent.data.brokerName
                      idChannel = authEvent.data.pspChannelCode
                      businessName = authEvent.data.pspBusinessName
                      pspOnUs = authEvent.data.isPspOnUs
                    }
                  authorizationCode = null
                  timestampOperation = authCompletedEvent.data.timestampOperation
                  paymentGateway = authEvent.data.paymentGateway.name
                }
              user = UserDto().apply { type = UserDto.TypeEnum.GUEST }
              info =
                InfoDto().apply {
                  type = authEvent.data.paymentTypeCode
                  clientId = Transaction.ClientId.CHECKOUT.name
                  brand = authEvent.data.paymentTypeCode
                  brandLogo =
                    (authEvent.data.transactionGatewayAuthorizationRequestedData
                        as NpgTransactionGatewayAuthorizationRequestedData)
                      .logo
                      .toString()
                  paymentMethodName = authEvent.data.paymentMethodName
                }
            }
          additionalPaymentInformations = null
        }

      assertEquals(expected, bancomatPayClosePaymentRequestCaptor.value)
    }

  @Test
  fun `ClosePaymentRequestV2Dto for close payment KO for authorization KO is correct for BancomatPay method`() =
    runTest {
      val transactionOutcome = ClosePaymentOutcome.KO
      val bancomatPayTransactionGatewayAuthorizationRequestedData =
        NpgTransactionGatewayAuthorizationRequestedData(
          LOGO_URI,
          NpgClient.PaymentMethod.BANCOMATPAY.toString(),
          "npgSessionId",
          "npgConfirmPaymentSessionId",
          null)
      val bancomatPayTransactionGatewayAuthorizationData =
        npgTransactionGatewayAuthorizationData(OperationResultDto.DECLINED)

      val activatedEvent = transactionActivateEvent()
      val authEvent =
        TransactionAuthorizationRequestedEvent(
          TRANSACTION_ID,
          TransactionAuthorizationRequestData(
            100,
            10,
            "paymentInstrumentId",
            "pspId",
            PaymentCode.BPAY.name,
            "brokerName",
            "pspChannelCode",
            "paymentMethodName",
            "pspBusinessName",
            false,
            AUTHORIZATION_REQUEST_ID,
            TransactionAuthorizationRequestData.PaymentGateway.NPG,
            "paymentMethodDescription",
            bancomatPayTransactionGatewayAuthorizationRequestedData))
      val authCompletedEvent =
        transactionAuthorizationCompletedEvent(bancomatPayTransactionGatewayAuthorizationData)
      val closureRequestedEvent = transactionClosureRequestedEvent()
      val closureError = transactionClosureErrorEvent()
      val transactionId = activatedEvent.transactionId
      val events =
        listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
          as List<TransactionEvent<Any>>

      val closePaymentResponse =
        ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }

      /* preconditions */
      given(
          transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(
            TRANSACTION_ID))
        .willReturn(events.toFlux())

      given(nodeClient.closePayment(capture(bancomatPayClosePaymentRequestCaptor)))
        .willReturn(Mono.just(closePaymentResponse))

      val fee = authEvent.data.fee
      val amount = authEvent.data.amount
      val totalAmount = amount + fee

      val feeEuroCents = BigDecimal(fee)
      val amountEuroCents = BigDecimal(amount)
      val totalAmountEuroCents = BigDecimal(totalAmount)

      /* test */
      assertEquals(
        closePaymentResponse,
        nodeService.closePayment(TransactionId(transactionId), transactionOutcome))

      val expected =
        BancomatPayClosePaymentRequestV2Dto().apply {
          outcome = BancomatPayClosePaymentRequestV2Dto.OutcomeEnum.KO
          this.transactionId = transactionId
          paymentTokens =
            activatedEvent.data.paymentNotices.map { paymentNotice -> paymentNotice.paymentToken }
          transactionDetails =
            TransactionDetailsDto().apply {
              transaction =
                TransactionDto().apply {
                  transactionStatus =
                    TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_DENIED.status
                  this.transactionId = transactionId
                  this.fee = feeEuroCents
                  this.amount = amountEuroCents
                  grandTotal = totalAmountEuroCents
                  this.errorCode =
                    (authCompletedEvent.data.transactionGatewayAuthorizationData
                        as NpgTransactionGatewayAuthorizationData)
                      .errorCode
                  rrn = authCompletedEvent.data.rrn
                  creationDate = ZonedDateTime.parse(activatedEvent.creationDate).toOffsetDateTime()
                  psp =
                    PspDto().apply {
                      idPsp = authEvent.data.pspId
                      brokerName = authEvent.data.brokerName
                      idChannel = authEvent.data.pspChannelCode
                      businessName = authEvent.data.pspBusinessName
                      pspOnUs = authEvent.data.isPspOnUs
                    }
                  authorizationCode = null
                  timestampOperation = authCompletedEvent.data.timestampOperation
                  paymentGateway = authEvent.data.paymentGateway.name
                }
              user = UserDto().apply { type = UserDto.TypeEnum.GUEST }
              info =
                InfoDto().apply {
                  type = authEvent.data.paymentTypeCode
                  clientId = Transaction.ClientId.CHECKOUT.name
                  brand = authEvent.data.paymentTypeCode
                  brandLogo =
                    (authEvent.data.transactionGatewayAuthorizationRequestedData
                        as NpgTransactionGatewayAuthorizationRequestedData)
                      .logo
                      .toString()
                  paymentMethodName = authEvent.data.paymentMethodName
                }
            }
          additionalPaymentInformations = null
        }

      assertEquals(expected, bancomatPayClosePaymentRequestCaptor.value)
    }

  @ParameterizedTest
  @MethodSource("closePaymentDateFormat")
  fun `ClosePaymentRequestV2Dto for close payment OK has additional properties and transaction details valued correctly for MyBank method`(
    timestampOperation: String,
    expectedLocalDate: String
  ) = runTest {
    val transactionOutcome = ClosePaymentOutcome.OK
    val myBankTransactionGatewayAuthorizationRequestedData =
      NpgTransactionGatewayAuthorizationRequestedData(
        LOGO_URI,
        NpgClient.PaymentMethod.MYBANK.toString(),
        "npgSessionId",
        "npgConfirmPaymentSessionId",
        null)
    val myBankTransactionGatewayAuthorizationData =
      npgTransactionGatewayAuthorizationData(OperationResultDto.EXECUTED)

    val activatedEvent = transactionActivateEvent()
    val authEvent =
      TransactionAuthorizationRequestedEvent(
        TRANSACTION_ID,
        TransactionAuthorizationRequestData(
          100,
          10,
          "paymentInstrumentId",
          "pspId",
          PaymentCode.MYBK.name,
          "brokerName",
          "pspChannelCode",
          "paymentMethodName",
          "pspBusinessName",
          false,
          AUTHORIZATION_REQUEST_ID,
          TransactionAuthorizationRequestData.PaymentGateway.NPG,
          "paymentMethodDescription",
          myBankTransactionGatewayAuthorizationRequestedData))
    val authCompletedEvent =
      transactionAuthorizationCompletedEvent(myBankTransactionGatewayAuthorizationData)
    val closureRequestedEvent = transactionClosureRequestedEvent()
    val closureError = transactionClosureErrorEvent()
    val transactionId = activatedEvent.transactionId
    val nodoTimestampOperation = OffsetDateTime.parse(timestampOperation)
    authCompletedEvent.data.timestampOperation = nodoTimestampOperation.toString()
    val events =
      listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
        as List<TransactionEvent<Any>>

    val closePaymentResponse =
      ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }

    /* preconditions */
    given(
        transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(TRANSACTION_ID))
      .willReturn(events.toFlux())
    given(confidentialDataUtils.eCommerceDecrypt(eq(activatedEvent.data.email), any()))
      .willReturn(Mono.just(Email(EMAIL_STRING)))

    given(nodeClient.closePayment(capture(myBankClosePaymentRequestCaptor)))
      .willReturn(Mono.just(closePaymentResponse))

    val fee = authEvent.data.fee
    val amount = authEvent.data.amount
    val totalAmount = amount + fee

    val feeEuro = EuroUtils.euroCentsToEuro(fee)
    val totalAmountEuro = EuroUtils.euroCentsToEuro(totalAmount)

    val feeEuroCents = BigDecimal(fee)
    val amountEuroCents = BigDecimal(amount)
    val totalAmountEuroCents = BigDecimal(totalAmount)

    /* test */
    assertEquals(
      closePaymentResponse,
      nodeService.closePayment(TransactionId(transactionId), transactionOutcome))
    val expectedTimestamp = expectedLocalDate

    val expected =
      MyBankClosePaymentRequestV2Dto().apply {
        outcome = MyBankClosePaymentRequestV2Dto.OutcomeEnum.OK
        this.transactionId = transactionId
        paymentTokens =
          activatedEvent.data.paymentNotices.map { paymentNotice -> paymentNotice.paymentToken }
        this.timestampOperation = OffsetDateTime.parse(authCompletedEvent.data.timestampOperation)
        this.fee = feeEuro
        idPSP = authEvent.data.pspId
        idChannel = authEvent.data.pspChannelCode
        idBrokerPSP = authEvent.data.brokerName
        paymentMethod = authEvent.data.paymentTypeCode
        this.totalAmount = totalAmountEuro
        transactionDetails =
          TransactionDetailsDto().apply {
            transaction =
              TransactionDto().apply {
                transactionStatus =
                  TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_CONFIRMED.status
                this.transactionId = transactionId
                this.fee = feeEuroCents
                this.amount = amountEuroCents
                grandTotal = totalAmountEuroCents
                this.errorCode = errorCode
                rrn = authCompletedEvent.data.rrn
                creationDate = ZonedDateTime.parse(activatedEvent.creationDate).toOffsetDateTime()
                psp =
                  PspDto().apply {
                    idPsp = authEvent.data.pspId
                    brokerName = authEvent.data.brokerName
                    idChannel = authEvent.data.pspChannelCode
                    businessName = authEvent.data.pspBusinessName
                    pspOnUs = authEvent.data.isPspOnUs
                  }
                authorizationCode = authCompletedEvent.data.authorizationCode
                this.timestampOperation = authCompletedEvent.data.timestampOperation
                paymentGateway = authEvent.data.paymentGateway.name
              }
            user = UserDto().apply { type = UserDto.TypeEnum.GUEST }
            info =
              InfoDto().apply {
                type = authEvent.data.paymentTypeCode
                clientId = Transaction.ClientId.CHECKOUT.name
                brand = authEvent.data.paymentTypeCode
                brandLogo =
                  (authEvent.data.transactionGatewayAuthorizationRequestedData
                      as NpgTransactionGatewayAuthorizationRequestedData)
                    .logo
                    .toString()
                paymentMethodName = authEvent.data.paymentMethodName
              }
          }
        additionalPaymentInformations =
          MyBankAdditionalPaymentInformationsDto().apply {
            this.transactionId = authCompletedEvent.transactionId
            this.myBankTransactionId =
              (authCompletedEvent.data.transactionGatewayAuthorizationData
                  as NpgTransactionGatewayAuthorizationData)
                .paymentEndToEndId
            this.totalAmount = totalAmountEuro.toString()
            this.fee = feeEuro.toString()
            this.validationServiceId = NPG_VALIDATION_SERVICE_ID
            this.timestampOperation = expectedTimestamp
            this.email = EMAIL_STRING
          }
      }

    assertEquals(expected, myBankClosePaymentRequestCaptor.value)
  }

  @Test
  fun `ClosePaymentRequestV2Dto for close payment KO for cancelled transaction is correct for MyBank method`() =
    runTest {
      val transactionOutcome = ClosePaymentOutcome.KO
      val myBankTransactionGatewayAuthorizationRequestedData =
        NpgTransactionGatewayAuthorizationRequestedData(
          LOGO_URI,
          NpgClient.PaymentMethod.MYBANK.toString(),
          "npgSessionId",
          "npgConfirmPaymentSessionId",
          null)
      val myBankTransactionGatewayAuthorizationData =
        npgTransactionGatewayAuthorizationData(OperationResultDto.DECLINED)

      val activatedEvent = transactionActivateEvent()
      val authEvent =
        TransactionAuthorizationRequestedEvent(
          TRANSACTION_ID,
          TransactionAuthorizationRequestData(
            100,
            10,
            "paymentInstrumentId",
            "pspId",
            PaymentCode.MYBK.name,
            "brokerName",
            "pspChannelCode",
            "paymentMethodName",
            "pspBusinessName",
            false,
            AUTHORIZATION_REQUEST_ID,
            TransactionAuthorizationRequestData.PaymentGateway.NPG,
            "paymentMethodDescription",
            myBankTransactionGatewayAuthorizationRequestedData))
      val authCompletedEvent =
        transactionAuthorizationCompletedEvent(myBankTransactionGatewayAuthorizationData)
      val closureRequestedEvent = transactionClosureRequestedEvent()
      val closureError = transactionClosureErrorEvent()
      val transactionId = activatedEvent.transactionId
      val events =
        listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
          as List<TransactionEvent<Any>>

      val closePaymentResponse =
        ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }

      /* preconditions */
      given(
          transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(
            TRANSACTION_ID))
        .willReturn(events.toFlux())

      given(nodeClient.closePayment(capture(bancomatPayClosePaymentRequestCaptor)))
        .willReturn(Mono.just(closePaymentResponse))

      val fee = authEvent.data.fee
      val amount = authEvent.data.amount
      val totalAmount = amount + fee

      val feeEuroCents = BigDecimal(fee)
      val amountEuroCents = BigDecimal(amount)
      val totalAmountEuroCents = BigDecimal(totalAmount)

      /* test */
      assertEquals(
        closePaymentResponse,
        nodeService.closePayment(TransactionId(transactionId), transactionOutcome))

      val expected =
        MyBankClosePaymentRequestV2Dto().apply {
          outcome = MyBankClosePaymentRequestV2Dto.OutcomeEnum.KO
          this.transactionId = transactionId
          paymentTokens =
            activatedEvent.data.paymentNotices.map { paymentNotice -> paymentNotice.paymentToken }
          transactionDetails =
            TransactionDetailsDto().apply {
              transaction =
                TransactionDto().apply {
                  transactionStatus =
                    TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_DENIED.status
                  this.transactionId = transactionId
                  this.fee = feeEuroCents
                  this.amount = amountEuroCents
                  grandTotal = totalAmountEuroCents
                  this.errorCode =
                    (authCompletedEvent.data.transactionGatewayAuthorizationData
                        as NpgTransactionGatewayAuthorizationData)
                      .errorCode
                  rrn = authCompletedEvent.data.rrn
                  creationDate = ZonedDateTime.parse(activatedEvent.creationDate).toOffsetDateTime()
                  psp =
                    PspDto().apply {
                      idPsp = authEvent.data.pspId
                      brokerName = authEvent.data.brokerName
                      idChannel = authEvent.data.pspChannelCode
                      businessName = authEvent.data.pspBusinessName
                      pspOnUs = authEvent.data.isPspOnUs
                    }
                  authorizationCode = null
                  timestampOperation = authCompletedEvent.data.timestampOperation
                  paymentGateway = authEvent.data.paymentGateway.name
                }
              user = UserDto().apply { type = UserDto.TypeEnum.GUEST }
              info =
                InfoDto().apply {
                  type = authEvent.data.paymentTypeCode
                  clientId = Transaction.ClientId.CHECKOUT.name
                  brand = authEvent.data.paymentTypeCode
                  brandLogo =
                    (authEvent.data.transactionGatewayAuthorizationRequestedData
                        as NpgTransactionGatewayAuthorizationRequestedData)
                      .logo
                      .toString()
                  paymentMethodName = authEvent.data.paymentMethodName
                }
            }
          additionalPaymentInformations = null
        }

      assertEquals(expected, bancomatPayClosePaymentRequestCaptor.value)
    }

  @Test
  fun `ClosePaymentRequestV2Dto for close payment KO for authorization KO is correct for MyBank method`() =
    runTest {
      val transactionOutcome = ClosePaymentOutcome.KO
      val myBankTransactionGatewayAuthorizationRequestedData =
        NpgTransactionGatewayAuthorizationRequestedData(
          LOGO_URI,
          NpgClient.PaymentMethod.MYBANK.toString(),
          "npgSessionId",
          "npgConfirmPaymentSessionId",
          null)
      val myBankTransactionGatewayAuthorizationData =
        npgTransactionGatewayAuthorizationData(OperationResultDto.DECLINED)

      val activatedEvent = transactionActivateEvent()
      val authEvent =
        TransactionAuthorizationRequestedEvent(
          TRANSACTION_ID,
          TransactionAuthorizationRequestData(
            100,
            10,
            "paymentInstrumentId",
            "pspId",
            PaymentCode.MYBK.name,
            "brokerName",
            "pspChannelCode",
            "paymentMethodName",
            "pspBusinessName",
            false,
            AUTHORIZATION_REQUEST_ID,
            TransactionAuthorizationRequestData.PaymentGateway.NPG,
            "paymentMethodDescription",
            myBankTransactionGatewayAuthorizationRequestedData))
      val authCompletedEvent =
        transactionAuthorizationCompletedEvent(myBankTransactionGatewayAuthorizationData)
      val closureRequestedEvent = transactionClosureRequestedEvent()
      val closureError = transactionClosureErrorEvent()
      val transactionId = activatedEvent.transactionId
      val events =
        listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
          as List<TransactionEvent<Any>>

      val closePaymentResponse =
        ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }

      /* preconditions */
      given(
          transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(
            TRANSACTION_ID))
        .willReturn(events.toFlux())

      given(nodeClient.closePayment(capture(bancomatPayClosePaymentRequestCaptor)))
        .willReturn(Mono.just(closePaymentResponse))

      val fee = authEvent.data.fee
      val amount = authEvent.data.amount
      val totalAmount = amount + fee

      val feeEuroCents = BigDecimal(fee)
      val amountEuroCents = BigDecimal(amount)
      val totalAmountEuroCents = BigDecimal(totalAmount)

      /* test */
      assertEquals(
        closePaymentResponse,
        nodeService.closePayment(TransactionId(transactionId), transactionOutcome))

      val expected =
        MyBankClosePaymentRequestV2Dto().apply {
          outcome = MyBankClosePaymentRequestV2Dto.OutcomeEnum.KO
          this.transactionId = transactionId
          paymentTokens =
            activatedEvent.data.paymentNotices.map { paymentNotice -> paymentNotice.paymentToken }
          transactionDetails =
            TransactionDetailsDto().apply {
              transaction =
                TransactionDto().apply {
                  transactionStatus =
                    TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_DENIED.status
                  this.transactionId = transactionId
                  this.fee = feeEuroCents
                  this.amount = amountEuroCents
                  grandTotal = totalAmountEuroCents
                  this.errorCode =
                    (authCompletedEvent.data.transactionGatewayAuthorizationData
                        as NpgTransactionGatewayAuthorizationData)
                      .errorCode
                  rrn = authCompletedEvent.data.rrn
                  creationDate = ZonedDateTime.parse(activatedEvent.creationDate).toOffsetDateTime()
                  psp =
                    PspDto().apply {
                      idPsp = authEvent.data.pspId
                      brokerName = authEvent.data.brokerName
                      idChannel = authEvent.data.pspChannelCode
                      businessName = authEvent.data.pspBusinessName
                      pspOnUs = authEvent.data.isPspOnUs
                    }
                  authorizationCode = null
                  timestampOperation = authCompletedEvent.data.timestampOperation
                  paymentGateway = authEvent.data.paymentGateway.name
                }
              user = UserDto().apply { type = UserDto.TypeEnum.GUEST }
              info =
                InfoDto().apply {
                  type = authEvent.data.paymentTypeCode
                  clientId = Transaction.ClientId.CHECKOUT.name
                  brand = authEvent.data.paymentTypeCode
                  brandLogo =
                    (authEvent.data.transactionGatewayAuthorizationRequestedData
                        as NpgTransactionGatewayAuthorizationRequestedData)
                      .logo
                      .toString()
                  paymentMethodName = authEvent.data.paymentMethodName
                }
            }
          additionalPaymentInformations = null
        }

      assertEquals(expected, bancomatPayClosePaymentRequestCaptor.value)
    }

  @ParameterizedTest
  @MethodSource("closePaymentDateFormat")
  fun `ClosePaymentRequestV2Dto for close payment OK has additional properties and transaction details valued correctly for card wallet`(
    timestampOperation: String,
    expectedLocalDate: String
  ) = runTest {
    val transactionOutcome = ClosePaymentOutcome.OK
    val authRequestedData =
      NpgTransactionGatewayAuthorizationRequestedData(
        LOGO_URI,
        NpgClient.PaymentMethod.CARDS.toString(),
        "npgSessionId",
        "npgConfirmPaymentSessionId",
        cardsWalletInfo())
    val authData = npgTransactionGatewayAuthorizationData(OperationResultDto.EXECUTED)

    val activatedEvent = transactionActivateEvent()
    val authEvent =
      TransactionAuthorizationRequestedEvent(
        TRANSACTION_ID,
        TransactionAuthorizationRequestData(
          100,
          10,
          "paymentInstrumentId",
          "pspId",
          PaymentCode.CP.name,
          "brokerName",
          "pspChannelCode",
          "paymentMethodName",
          "pspBusinessName",
          false,
          AUTHORIZATION_REQUEST_ID,
          TransactionAuthorizationRequestData.PaymentGateway.NPG,
          "paymentMethodDescription",
          authRequestedData))
    val authCompletedEvent = transactionAuthorizationCompletedEvent(authData)
    val closureRequestedEvent = transactionClosureRequestedEvent()
    val closureError = transactionClosureErrorEvent()
    val transactionId = activatedEvent.transactionId
    val nodoTimestampOperation = OffsetDateTime.parse(timestampOperation)
    authCompletedEvent.data.timestampOperation = nodoTimestampOperation.toString()
    val events =
      listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
        as List<TransactionEvent<Any>>

    val closePaymentResponse =
      ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }

    /* preconditions */
    given(
        transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(TRANSACTION_ID))
      .willReturn(events.toFlux())

    given(nodeClient.closePayment(capture(closePaymentRequestCaptor)))
      .willReturn(Mono.just(closePaymentResponse))

    given(confidentialDataUtils.eCommerceDecrypt(eq(activatedEvent.data.email), any()))
      .willReturn(Mono.just(Email(EMAIL_STRING)))

    val fee = authEvent.data.fee
    val amount = authEvent.data.amount
    val totalAmount = amount + fee

    val feeEuro = EuroUtils.euroCentsToEuro(fee)
    val totalAmountEuro = EuroUtils.euroCentsToEuro(totalAmount)

    val feeEuroCents = BigDecimal(fee)
    val amountEuroCents = BigDecimal(amount)
    val totalAmountEuroCents = BigDecimal(totalAmount)

    /* test */
    assertEquals(
      closePaymentResponse,
      nodeService.closePayment(TransactionId(transactionId), transactionOutcome))
    val expectedTimestamp = expectedLocalDate

    val expected =
      CardClosePaymentRequestV2Dto().apply {
        outcome = CardClosePaymentRequestV2Dto.OutcomeEnum.OK
        this.transactionId = transactionId
        paymentTokens =
          activatedEvent.data.paymentNotices.map { paymentNotice -> paymentNotice.paymentToken }
        this.timestampOperation = OffsetDateTime.parse(authCompletedEvent.data.timestampOperation)
        this.fee = feeEuro
        idPSP = authEvent.data.pspId
        idChannel = authEvent.data.pspChannelCode
        idBrokerPSP = authEvent.data.brokerName
        paymentMethod = authEvent.data.paymentTypeCode
        this.totalAmount = totalAmountEuro
        transactionDetails =
          TransactionDetailsDto().apply {
            transaction =
              TransactionDto().apply {
                transactionStatus =
                  TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_CONFIRMED.status
                this.transactionId = transactionId
                this.fee = feeEuroCents
                this.amount = amountEuroCents
                grandTotal = totalAmountEuroCents
                this.errorCode = errorCode
                rrn = authCompletedEvent.data.rrn
                creationDate = ZonedDateTime.parse(activatedEvent.creationDate).toOffsetDateTime()
                psp =
                  PspDto().apply {
                    idPsp = authEvent.data.pspId
                    brokerName = authEvent.data.brokerName
                    idChannel = authEvent.data.pspChannelCode
                    businessName = authEvent.data.pspBusinessName
                    pspOnUs = authEvent.data.isPspOnUs
                  }
                authorizationCode = authCompletedEvent.data.authorizationCode
                this.timestampOperation = authCompletedEvent.data.timestampOperation
                paymentGateway = authEvent.data.paymentGateway.name
              }
            user = UserDto().apply { type = UserDto.TypeEnum.GUEST }
            info =
              InfoDto().apply {
                type = authEvent.data.paymentTypeCode
                clientId = Transaction.ClientId.CHECKOUT.name
                brand = authEvent.data.paymentTypeCode
                brandLogo =
                  (authEvent.data.transactionGatewayAuthorizationRequestedData
                      as NpgTransactionGatewayAuthorizationRequestedData)
                    .logo
                    .toString()
                paymentMethodName = authEvent.data.paymentMethodName
                bin = NPG_WALLET_CARD_BIN
                lastFourDigits = NPG_WALLET_CARD_LAST_FOUR_DIGITS
              }
          }
        additionalPaymentInformations =
          CardAdditionalPaymentInformationsDto().apply {
            this.authorizationCode = authCompletedEvent.data.authorizationCode
            this.fee = feeEuro.toString()
            this.outcomePaymentGateway = OutcomePaymentGatewayEnum.OK
            this.rrn = authCompletedEvent.data.rrn
            this.timestampOperation = expectedTimestamp
            this.fee = feeEuro.toString()
            this.totalAmount = totalAmountEuro.toString()
            this.email = EMAIL_STRING
          }
      }

    assertEquals(expected, closePaymentRequestCaptor.value)
  }

  @Test
  fun `ClosePaymentRequestV2Dto for close payment KO for cancelled transaction is correct for card wallet`() =
    runTest {
      val transactionOutcome = ClosePaymentOutcome.KO
      val authRequestedData =
        NpgTransactionGatewayAuthorizationRequestedData(
          LOGO_URI,
          NpgClient.PaymentMethod.CARDS.toString(),
          "npgSessionId",
          "npgConfirmPaymentSessionId",
          cardsWalletInfo())
      val authCompletedData = npgTransactionGatewayAuthorizationData(OperationResultDto.DECLINED)

      val activatedEvent = transactionActivateEvent()
      val authEvent =
        TransactionAuthorizationRequestedEvent(
          TRANSACTION_ID,
          TransactionAuthorizationRequestData(
            100,
            10,
            "paymentInstrumentId",
            "pspId",
            PaymentCode.CP.name,
            "brokerName",
            "pspChannelCode",
            "paymentMethodName",
            "pspBusinessName",
            false,
            AUTHORIZATION_REQUEST_ID,
            TransactionAuthorizationRequestData.PaymentGateway.NPG,
            "paymentMethodDescription",
            authRequestedData))
      val authCompletedEvent = transactionAuthorizationCompletedEvent(authCompletedData)
      val closureRequestedEvent = transactionClosureRequestedEvent()
      val closureError = transactionClosureErrorEvent()
      val transactionId = activatedEvent.transactionId
      val events =
        listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
          as List<TransactionEvent<Any>>

      val closePaymentResponse =
        ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }

      /* preconditions */
      given(
          transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(
            TRANSACTION_ID))
        .willReturn(events.toFlux())

      given(nodeClient.closePayment(capture(closePaymentRequestCaptor)))
        .willReturn(Mono.just(closePaymentResponse))

      val fee = authEvent.data.fee
      val amount = authEvent.data.amount
      val totalAmount = amount + fee

      val feeEuroCents = BigDecimal(fee)
      val amountEuroCents = BigDecimal(amount)
      val totalAmountEuroCents = BigDecimal(totalAmount)

      /* test */
      assertEquals(
        closePaymentResponse,
        nodeService.closePayment(TransactionId(transactionId), transactionOutcome))

      val expected =
        CardClosePaymentRequestV2Dto().apply {
          outcome = CardClosePaymentRequestV2Dto.OutcomeEnum.KO
          this.transactionId = transactionId
          paymentTokens =
            activatedEvent.data.paymentNotices.map { paymentNotice -> paymentNotice.paymentToken }
          transactionDetails =
            TransactionDetailsDto().apply {
              transaction =
                TransactionDto().apply {
                  transactionStatus =
                    TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_DENIED.status
                  this.transactionId = transactionId
                  this.fee = feeEuroCents
                  this.amount = amountEuroCents
                  grandTotal = totalAmountEuroCents
                  this.errorCode =
                    (authCompletedEvent.data.transactionGatewayAuthorizationData
                        as NpgTransactionGatewayAuthorizationData)
                      .errorCode
                  rrn = authCompletedEvent.data.rrn
                  creationDate = ZonedDateTime.parse(activatedEvent.creationDate).toOffsetDateTime()
                  psp =
                    PspDto().apply {
                      idPsp = authEvent.data.pspId
                      brokerName = authEvent.data.brokerName
                      idChannel = authEvent.data.pspChannelCode
                      businessName = authEvent.data.pspBusinessName
                      pspOnUs = authEvent.data.isPspOnUs
                    }
                  authorizationCode = null
                  timestampOperation = authCompletedEvent.data.timestampOperation
                  paymentGateway = authEvent.data.paymentGateway.name
                }
              user = UserDto().apply { type = UserDto.TypeEnum.GUEST }
              info =
                InfoDto().apply {
                  type = authEvent.data.paymentTypeCode
                  clientId = Transaction.ClientId.CHECKOUT.name
                  brand = authEvent.data.paymentTypeCode
                  brandLogo =
                    (authEvent.data.transactionGatewayAuthorizationRequestedData
                        as NpgTransactionGatewayAuthorizationRequestedData)
                      .logo
                      .toString()
                  paymentMethodName = authEvent.data.paymentMethodName
                  bin = NPG_WALLET_CARD_BIN
                  lastFourDigits = NPG_WALLET_CARD_LAST_FOUR_DIGITS
                }
            }
          additionalPaymentInformations = null
        }

      assertEquals(expected, closePaymentRequestCaptor.value)
    }

  @Test
  fun `ClosePaymentRequestV2Dto for close payment KO for authorization KO is correct for card wallet`() =
    runTest {
      val transactionOutcome = ClosePaymentOutcome.KO
      val authRequestedData =
        NpgTransactionGatewayAuthorizationRequestedData(
          LOGO_URI,
          NpgClient.PaymentMethod.PAYPAL.toString(),
          "npgSessionId",
          "npgConfirmPaymentSessionId",
          cardsWalletInfo())
      val authCompletedData = npgTransactionGatewayAuthorizationData(OperationResultDto.DECLINED)

      val activatedEvent = transactionActivateEvent()
      val authEvent =
        TransactionAuthorizationRequestedEvent(
          TRANSACTION_ID,
          TransactionAuthorizationRequestData(
            100,
            10,
            "paymentInstrumentId",
            "pspId",
            PaymentCode.PPAL.name,
            "brokerName",
            "pspChannelCode",
            "paymentMethodName",
            "pspBusinessName",
            false,
            AUTHORIZATION_REQUEST_ID,
            TransactionAuthorizationRequestData.PaymentGateway.NPG,
            "paymentMethodDescription",
            authRequestedData))
      val authCompletedEvent = transactionAuthorizationCompletedEvent(authCompletedData)
      val closureRequestedEvent = transactionClosureRequestedEvent()
      val closureError = transactionClosureErrorEvent()
      val transactionId = activatedEvent.transactionId
      val events =
        listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
          as List<TransactionEvent<Any>>

      val closePaymentResponse =
        ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }

      /* preconditions */
      given(
          transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(
            TRANSACTION_ID))
        .willReturn(events.toFlux())

      given(nodeClient.closePayment(capture(paypalClosePaymentRequestCaptor)))
        .willReturn(Mono.just(closePaymentResponse))

      val fee = authEvent.data.fee
      val amount = authEvent.data.amount
      val totalAmount = amount + fee

      val feeEuroCents = BigDecimal(fee)
      val amountEuroCents = BigDecimal(amount)
      val totalAmountEuroCents = BigDecimal(totalAmount)

      /* test */
      assertEquals(
        closePaymentResponse,
        nodeService.closePayment(TransactionId(transactionId), transactionOutcome))

      val expected =
        PayPalClosePaymentRequestV2Dto().apply {
          outcome = PayPalClosePaymentRequestV2Dto.OutcomeEnum.KO
          this.transactionId = transactionId
          paymentTokens =
            activatedEvent.data.paymentNotices.map { paymentNotice -> paymentNotice.paymentToken }
          transactionDetails =
            TransactionDetailsDto().apply {
              transaction =
                TransactionDto().apply {
                  transactionStatus =
                    TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_DENIED.status
                  this.transactionId = transactionId
                  this.fee = feeEuroCents
                  this.amount = amountEuroCents
                  grandTotal = totalAmountEuroCents
                  this.errorCode =
                    (authCompletedEvent.data.transactionGatewayAuthorizationData
                        as NpgTransactionGatewayAuthorizationData)
                      .errorCode
                  rrn = authCompletedEvent.data.rrn
                  creationDate = ZonedDateTime.parse(activatedEvent.creationDate).toOffsetDateTime()
                  psp =
                    PspDto().apply {
                      idPsp = authEvent.data.pspId
                      brokerName = authEvent.data.brokerName
                      idChannel = authEvent.data.pspChannelCode
                      businessName = authEvent.data.pspBusinessName
                      pspOnUs = authEvent.data.isPspOnUs
                    }
                  authorizationCode = null
                  timestampOperation = authCompletedEvent.data.timestampOperation
                  paymentGateway = authEvent.data.paymentGateway.name
                }
              user = UserDto().apply { type = UserDto.TypeEnum.GUEST }
              info =
                InfoDto().apply {
                  type = authEvent.data.paymentTypeCode
                  clientId = Transaction.ClientId.CHECKOUT.name
                  brand = authEvent.data.paymentTypeCode
                  brandLogo =
                    (authEvent.data.transactionGatewayAuthorizationRequestedData
                        as NpgTransactionGatewayAuthorizationRequestedData)
                      .logo
                      .toString()
                  paymentMethodName = authEvent.data.paymentMethodName
                  bin = NPG_WALLET_CARD_BIN
                  lastFourDigits = NPG_WALLET_CARD_LAST_FOUR_DIGITS
                }
            }
          additionalPaymentInformations = null
        }

      assertEquals(expected, paypalClosePaymentRequestCaptor.value)
    }

  @ParameterizedTest
  @MethodSource("closePaymentDateFormat")
  fun `ClosePaymentRequestV2Dto for close payment OK has additional properties and transaction details valued correctly for paypal wallet`(
    timestampOperation: String,
    expectedLocalDate: String
  ) = runTest {
    val transactionOutcome = ClosePaymentOutcome.OK
    val authRequestedData =
      NpgTransactionGatewayAuthorizationRequestedData(
        LOGO_URI,
        NpgClient.PaymentMethod.PAYPAL.toString(),
        "npgSessionId",
        "npgConfirmPaymentSessionId",
        paypalWalletInfo())
    val authData = npgTransactionGatewayAuthorizationData(OperationResultDto.EXECUTED)

    val activatedEvent = transactionActivateEvent()
    val authEvent =
      TransactionAuthorizationRequestedEvent(
        TRANSACTION_ID,
        TransactionAuthorizationRequestData(
          100,
          10,
          "paymentInstrumentId",
          "pspId",
          PaymentCode.PPAL.name,
          "brokerName",
          "pspChannelCode",
          "paymentMethodName",
          "pspBusinessName",
          false,
          AUTHORIZATION_REQUEST_ID,
          TransactionAuthorizationRequestData.PaymentGateway.NPG,
          "paymentMethodDescription",
          authRequestedData))
    val authCompletedEvent = transactionAuthorizationCompletedEvent(authData)
    val closureRequestedEvent = transactionClosureRequestedEvent()
    val closureError = transactionClosureErrorEvent()
    val transactionId = activatedEvent.transactionId
    val nodoTimestampOperation = OffsetDateTime.parse(timestampOperation)
    authCompletedEvent.data.timestampOperation = nodoTimestampOperation.toString()
    val events =
      listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
        as List<TransactionEvent<Any>>

    val closePaymentResponse =
      ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }

    /* preconditions */
    given(
        transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(TRANSACTION_ID))
      .willReturn(events.toFlux())
    given(confidentialDataUtils.eCommerceDecrypt(eq(activatedEvent.data.email), any()))
      .willReturn(Mono.just(Email(EMAIL_STRING)))

    given(nodeClient.closePayment(capture(paypalClosePaymentRequestCaptor)))
      .willReturn(Mono.just(closePaymentResponse))

    val fee = authEvent.data.fee
    val amount = authEvent.data.amount
    val totalAmount = amount + fee

    val feeEuro = EuroUtils.euroCentsToEuro(fee)
    val totalAmountEuro = EuroUtils.euroCentsToEuro(totalAmount)

    val feeEuroCents = BigDecimal(fee)
    val amountEuroCents = BigDecimal(amount)
    val totalAmountEuroCents = BigDecimal(totalAmount)

    /* test */
    assertEquals(
      closePaymentResponse,
      nodeService.closePayment(TransactionId(transactionId), transactionOutcome))
    val expectedTimestamp = expectedLocalDate

    val expected =
      PayPalClosePaymentRequestV2Dto().apply {
        outcome = PayPalClosePaymentRequestV2Dto.OutcomeEnum.OK
        this.transactionId = transactionId
        paymentTokens =
          activatedEvent.data.paymentNotices.map { paymentNotice -> paymentNotice.paymentToken }
        this.timestampOperation = OffsetDateTime.parse(authCompletedEvent.data.timestampOperation)
        this.fee = feeEuro
        idPSP = authEvent.data.pspId
        idChannel = authEvent.data.pspChannelCode
        idBrokerPSP = authEvent.data.brokerName
        paymentMethod = authEvent.data.paymentTypeCode
        this.totalAmount = totalAmountEuro
        transactionDetails =
          TransactionDetailsDto().apply {
            transaction =
              TransactionDto().apply {
                transactionStatus =
                  TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_CONFIRMED.status
                this.transactionId = transactionId
                this.fee = feeEuroCents
                this.amount = amountEuroCents
                grandTotal = totalAmountEuroCents
                this.errorCode = errorCode
                rrn = authCompletedEvent.data.rrn
                creationDate = ZonedDateTime.parse(activatedEvent.creationDate).toOffsetDateTime()
                psp =
                  PspDto().apply {
                    idPsp = authEvent.data.pspId
                    brokerName = authEvent.data.brokerName
                    idChannel = authEvent.data.pspChannelCode
                    businessName = authEvent.data.pspBusinessName
                    pspOnUs = authEvent.data.isPspOnUs
                  }
                authorizationCode = authCompletedEvent.data.authorizationCode
                this.timestampOperation = authCompletedEvent.data.timestampOperation
                paymentGateway = authEvent.data.paymentGateway.name
              }
            user = UserDto().apply { type = UserDto.TypeEnum.GUEST }
            info =
              InfoDto().apply {
                type = authEvent.data.paymentTypeCode
                clientId = Transaction.ClientId.CHECKOUT.name
                brand = authEvent.data.paymentTypeCode
                brandLogo =
                  (authEvent.data.transactionGatewayAuthorizationRequestedData
                      as NpgTransactionGatewayAuthorizationRequestedData)
                    .logo
                    .toString()
                paymentMethodName = authEvent.data.paymentMethodName
                maskedEmail = NPG_WALLET_PAYPAL_MASKED_EMAIL
              }
          }
        additionalPaymentInformations =
          PayPalAdditionalPaymentInformationsDto().apply {
            this.transactionId =
              (authCompletedEvent.data.transactionGatewayAuthorizationData
                  as NpgTransactionGatewayAuthorizationData)
                .operationId
            this.pspTransactionId =
              (authCompletedEvent.data.transactionGatewayAuthorizationData
                  as NpgTransactionGatewayAuthorizationData)
                .paymentEndToEndId
            this.timestampOperation = expectedTimestamp
            this.fee = feeEuro.toString()
            this.totalAmount = totalAmountEuro.toString()
            this.email = EMAIL_STRING
          }
      }

    assertEquals(expected, paypalClosePaymentRequestCaptor.value)
  }

  @Test
  fun `ClosePaymentRequestV2Dto for close payment KO for cancelled transaction is correct for paypal wallet`() =
    runTest {
      val transactionOutcome = ClosePaymentOutcome.KO
      val authRequestedData =
        NpgTransactionGatewayAuthorizationRequestedData(
          LOGO_URI,
          NpgClient.PaymentMethod.PAYPAL.toString(),
          "npgSessionId",
          "npgConfirmPaymentSessionId",
          paypalWalletInfo())
      val authCompletedData = npgTransactionGatewayAuthorizationData(OperationResultDto.DECLINED)

      val activatedEvent = transactionActivateEvent()
      val authEvent =
        TransactionAuthorizationRequestedEvent(
          TRANSACTION_ID,
          TransactionAuthorizationRequestData(
            100,
            10,
            "paymentInstrumentId",
            "pspId",
            PaymentCode.PPAL.name,
            "brokerName",
            "pspChannelCode",
            "paymentMethodName",
            "pspBusinessName",
            false,
            AUTHORIZATION_REQUEST_ID,
            TransactionAuthorizationRequestData.PaymentGateway.NPG,
            "paymentMethodDescription",
            authRequestedData))
      val authCompletedEvent = transactionAuthorizationCompletedEvent(authCompletedData)
      val closureRequestedEvent = transactionClosureRequestedEvent()
      val closureError = transactionClosureErrorEvent()
      val transactionId = activatedEvent.transactionId
      val events =
        listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
          as List<TransactionEvent<Any>>

      val closePaymentResponse =
        ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }

      /* preconditions */
      given(
          transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(
            TRANSACTION_ID))
        .willReturn(events.toFlux())

      given(nodeClient.closePayment(capture(paypalClosePaymentRequestCaptor)))
        .willReturn(Mono.just(closePaymentResponse))

      val fee = authEvent.data.fee
      val amount = authEvent.data.amount
      val totalAmount = amount + fee

      val feeEuroCents = BigDecimal(fee)
      val amountEuroCents = BigDecimal(amount)
      val totalAmountEuroCents = BigDecimal(totalAmount)

      /* test */
      assertEquals(
        closePaymentResponse,
        nodeService.closePayment(TransactionId(transactionId), transactionOutcome))

      val expected =
        PayPalClosePaymentRequestV2Dto().apply {
          outcome = PayPalClosePaymentRequestV2Dto.OutcomeEnum.KO
          this.transactionId = transactionId
          paymentTokens =
            activatedEvent.data.paymentNotices.map { paymentNotice -> paymentNotice.paymentToken }
          transactionDetails =
            TransactionDetailsDto().apply {
              transaction =
                TransactionDto().apply {
                  transactionStatus =
                    TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_DENIED.status
                  this.transactionId = transactionId
                  this.fee = feeEuroCents
                  this.amount = amountEuroCents
                  grandTotal = totalAmountEuroCents
                  this.errorCode =
                    (authCompletedEvent.data.transactionGatewayAuthorizationData
                        as NpgTransactionGatewayAuthorizationData)
                      .errorCode
                  rrn = authCompletedEvent.data.rrn
                  creationDate = ZonedDateTime.parse(activatedEvent.creationDate).toOffsetDateTime()
                  psp =
                    PspDto().apply {
                      idPsp = authEvent.data.pspId
                      brokerName = authEvent.data.brokerName
                      idChannel = authEvent.data.pspChannelCode
                      businessName = authEvent.data.pspBusinessName
                      pspOnUs = authEvent.data.isPspOnUs
                    }
                  authorizationCode = null
                  timestampOperation = authCompletedEvent.data.timestampOperation
                  paymentGateway = authEvent.data.paymentGateway.name
                }
              user = UserDto().apply { type = UserDto.TypeEnum.GUEST }
              info =
                InfoDto().apply {
                  type = authEvent.data.paymentTypeCode
                  clientId = Transaction.ClientId.CHECKOUT.name
                  brand = authEvent.data.paymentTypeCode
                  brandLogo =
                    (authEvent.data.transactionGatewayAuthorizationRequestedData
                        as NpgTransactionGatewayAuthorizationRequestedData)
                      .logo
                      .toString()
                  paymentMethodName = authEvent.data.paymentMethodName
                  maskedEmail = NPG_WALLET_PAYPAL_MASKED_EMAIL
                }
            }
          additionalPaymentInformations = null
        }

      assertEquals(expected, paypalClosePaymentRequestCaptor.value)
    }

  @Test
  fun `ClosePaymentRequestV2Dto for close payment KO for authorization KO is correct for paypal wallet`() =
    runTest {
      val transactionOutcome = ClosePaymentOutcome.KO
      val authRequestedData =
        NpgTransactionGatewayAuthorizationRequestedData(
          LOGO_URI,
          NpgClient.PaymentMethod.PAYPAL.toString(),
          "npgSessionId",
          "npgConfirmPaymentSessionId",
          paypalWalletInfo())
      val authCompletedData = npgTransactionGatewayAuthorizationData(OperationResultDto.DECLINED)

      val activatedEvent = transactionActivateEvent()
      val authEvent =
        TransactionAuthorizationRequestedEvent(
          TRANSACTION_ID,
          TransactionAuthorizationRequestData(
            100,
            10,
            "paymentInstrumentId",
            "pspId",
            PaymentCode.PPAL.name,
            "brokerName",
            "pspChannelCode",
            "paymentMethodName",
            "pspBusinessName",
            false,
            AUTHORIZATION_REQUEST_ID,
            TransactionAuthorizationRequestData.PaymentGateway.NPG,
            "paymentMethodDescription",
            authRequestedData))
      val authCompletedEvent = transactionAuthorizationCompletedEvent(authCompletedData)
      val closureRequestedEvent = transactionClosureRequestedEvent()
      val closureError = transactionClosureErrorEvent()
      val transactionId = activatedEvent.transactionId
      val events =
        listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
          as List<TransactionEvent<Any>>

      val closePaymentResponse =
        ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }

      /* preconditions */
      given(
          transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(
            TRANSACTION_ID))
        .willReturn(events.toFlux())

      given(nodeClient.closePayment(capture(paypalClosePaymentRequestCaptor)))
        .willReturn(Mono.just(closePaymentResponse))

      val fee = authEvent.data.fee
      val amount = authEvent.data.amount
      val totalAmount = amount + fee

      val feeEuroCents = BigDecimal(fee)
      val amountEuroCents = BigDecimal(amount)
      val totalAmountEuroCents = BigDecimal(totalAmount)

      /* test */
      assertEquals(
        closePaymentResponse,
        nodeService.closePayment(TransactionId(transactionId), transactionOutcome))

      val expected =
        PayPalClosePaymentRequestV2Dto().apply {
          outcome = PayPalClosePaymentRequestV2Dto.OutcomeEnum.KO
          this.transactionId = transactionId
          paymentTokens =
            activatedEvent.data.paymentNotices.map { paymentNotice -> paymentNotice.paymentToken }
          transactionDetails =
            TransactionDetailsDto().apply {
              transaction =
                TransactionDto().apply {
                  transactionStatus =
                    TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_DENIED.status
                  this.transactionId = transactionId
                  this.fee = feeEuroCents
                  this.amount = amountEuroCents
                  grandTotal = totalAmountEuroCents
                  this.errorCode =
                    (authCompletedEvent.data.transactionGatewayAuthorizationData
                        as NpgTransactionGatewayAuthorizationData)
                      .errorCode
                  rrn = authCompletedEvent.data.rrn
                  creationDate = ZonedDateTime.parse(activatedEvent.creationDate).toOffsetDateTime()
                  psp =
                    PspDto().apply {
                      idPsp = authEvent.data.pspId
                      brokerName = authEvent.data.brokerName
                      idChannel = authEvent.data.pspChannelCode
                      businessName = authEvent.data.pspBusinessName
                      pspOnUs = authEvent.data.isPspOnUs
                    }
                  authorizationCode = null
                  timestampOperation = authCompletedEvent.data.timestampOperation
                  paymentGateway = authEvent.data.paymentGateway.name
                }
              user = UserDto().apply { type = UserDto.TypeEnum.GUEST }
              info =
                InfoDto().apply {
                  type = authEvent.data.paymentTypeCode
                  clientId = Transaction.ClientId.CHECKOUT.name
                  brand = authEvent.data.paymentTypeCode
                  brandLogo =
                    (authEvent.data.transactionGatewayAuthorizationRequestedData
                        as NpgTransactionGatewayAuthorizationRequestedData)
                      .logo
                      .toString()
                  paymentMethodName = authEvent.data.paymentMethodName
                  maskedEmail = NPG_WALLET_PAYPAL_MASKED_EMAIL
                }
            }
          additionalPaymentInformations = null
        }

      assertEquals(expected, paypalClosePaymentRequestCaptor.value)
    }

  @ParameterizedTest
  @MethodSource("closePaymentDateFormat")
  fun `ClosePaymentRequestV2Dto for close payment OK has additional properties and transaction details valued correctly for REDIRECT payment gateway for registered user`(
    timestampOperation: String,
    expectedLocalDate: String
  ) = runTest {
    val transactionOutcome = ClosePaymentOutcome.OK
    val redirectTransactionGatewayAuthorizationRequestedData =
      redirectTransactionGatewayAuthorizationRequestedData()
        as RedirectTransactionGatewayAuthorizationRequestedData
    val redirectTransactionGatewayAuthorizationData =
      redirectTransactionGatewayAuthorizationData(
        RedirectTransactionGatewayAuthorizationData.Outcome.OK, "")
        as RedirectTransactionGatewayAuthorizationData

    val activatedEvent = transactionActivateEvent()
    activatedEvent.data.clientId = Transaction.ClientId.IO
    val authEvent =
      transactionAuthorizationRequestedEvent(
        TransactionAuthorizationRequestData.PaymentGateway.REDIRECT,
        redirectTransactionGatewayAuthorizationRequestedData)
    val authCompletedEvent =
      transactionAuthorizationCompletedEvent(redirectTransactionGatewayAuthorizationData)
    val closureRequestedEvent = transactionClosureRequestedEvent()
    val closureError = transactionClosureErrorEvent()
    val transactionId = activatedEvent.transactionId
    val nodoTimestampOperation = OffsetDateTime.parse(timestampOperation)
    authCompletedEvent.data.timestampOperation = nodoTimestampOperation.toString()
    val events =
      listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
        as List<TransactionEvent<Any>>

    val closePaymentResponse =
      ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }
    val userFiscalCode = "userFiscalCode"

    /* preconditions */
    given(confidentialDataUtils.decryptWalletSessionToken(any()))
      .willReturn(mono { userFiscalCode })
    given(
        transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(TRANSACTION_ID))
      .willReturn(events.toFlux())

    given(nodeClient.closePayment(capture(redirectClosePaymentRequestCaptor)))
      .willReturn(Mono.just(closePaymentResponse))

    val fee = authEvent.data.fee
    val amount = authEvent.data.amount
    val totalAmount = amount + fee

    val feeEuro = EuroUtils.euroCentsToEuro(fee)
    val totalAmountEuro = EuroUtils.euroCentsToEuro(totalAmount)

    val feeEuroCents = BigDecimal(fee)
    val amountEuroCents = BigDecimal(amount)
    val totalAmountEuroCents = BigDecimal(totalAmount)

    /* test */
    assertEquals(
      closePaymentResponse,
      nodeService.closePayment(TransactionId(transactionId), transactionOutcome))
    val expectedTimestamp = expectedLocalDate

    val expected =
      RedirectClosePaymentRequestV2Dto().apply {
        outcome = RedirectClosePaymentRequestV2Dto.OutcomeEnum.OK
        this.transactionId = transactionId
        paymentTokens =
          activatedEvent.data.paymentNotices.map { paymentNotice -> paymentNotice.paymentToken }
        this.timestampOperation = OffsetDateTime.parse(authCompletedEvent.data.timestampOperation)
        this.fee = feeEuro
        idPSP = authEvent.data.pspId
        idChannel = authEvent.data.pspChannelCode
        idBrokerPSP = authEvent.data.brokerName
        paymentMethod = authEvent.data.paymentTypeCode
        this.totalAmount = totalAmountEuro
        transactionDetails =
          TransactionDetailsDto().apply {
            transaction =
              TransactionDto().apply {
                transactionStatus =
                  TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_CONFIRMED.status
                this.transactionId = transactionId
                this.fee = feeEuroCents
                this.amount = amountEuroCents
                grandTotal = totalAmountEuroCents
                this.errorCode = errorCode
                rrn = authCompletedEvent.data.rrn
                creationDate = ZonedDateTime.parse(activatedEvent.creationDate).toOffsetDateTime()
                psp =
                  PspDto().apply {
                    idPsp = authEvent.data.pspId
                    brokerName = authEvent.data.brokerName
                    idChannel = authEvent.data.pspChannelCode
                    businessName = authEvent.data.pspBusinessName
                    pspOnUs = authEvent.data.isPspOnUs
                  }
                authorizationCode = authCompletedEvent.data.authorizationCode
                this.timestampOperation = authCompletedEvent.data.timestampOperation
                paymentGateway = authEvent.data.paymentGateway.name
              }
            user =
              UserDto().apply {
                type = UserDto.TypeEnum.REGISTERED
                fiscalCode = userFiscalCode
              }
            info =
              InfoDto().apply {
                type = authEvent.data.paymentTypeCode
                clientId = Transaction.ClientId.IO.name
                brand = authEvent.data.paymentTypeCode
                brandLogo =
                  (authEvent.data.transactionGatewayAuthorizationRequestedData
                      as RedirectTransactionGatewayAuthorizationRequestedData)
                    .logo
                    .toString()
                paymentMethodName = authEvent.data.paymentMethodName
              }
          }
        additionalPaymentInformations =
          RedirectAdditionalPaymentInformationsDto().apply {
            this.timestampOperation = expectedTimestamp
            idPSPTransaction = authEvent.data.authorizationRequestId
            this.fee = feeEuro.toString()
            this.totalAmount = totalAmountEuro.toString()
            authorizationCode = authCompletedEvent.data.authorizationCode
            idTransaction = TRANSACTION_ID
          }
      }

    assertEquals(expected, redirectClosePaymentRequestCaptor.value)
  }

  @Test
  fun `ClosePaymentRequestV2Dto for close payment KO for cancelled transaction is correct for REDIRECT payment gateway for registered user`() =
    runTest {
      val transactionOutcome = ClosePaymentOutcome.KO
      val redirectTransactionGatewayAuthorizationRequestedData =
        redirectTransactionGatewayAuthorizationRequestedData()
          as RedirectTransactionGatewayAuthorizationRequestedData
      val redirectTransactionGatewayAuthorizationData =
        redirectTransactionGatewayAuthorizationData(
          RedirectTransactionGatewayAuthorizationData.Outcome.KO, "errorCode")
          as RedirectTransactionGatewayAuthorizationData

      val activatedEvent = transactionActivateEvent()
      activatedEvent.data.clientId = Transaction.ClientId.IO
      val authEvent =
        transactionAuthorizationRequestedEvent(
          TransactionAuthorizationRequestData.PaymentGateway.REDIRECT,
          redirectTransactionGatewayAuthorizationRequestedData)
      val authCompletedEvent =
        transactionAuthorizationCompletedEvent(redirectTransactionGatewayAuthorizationData)
      val closureRequestedEvent = transactionClosureRequestedEvent()
      val closureError = transactionClosureErrorEvent()
      val transactionId = activatedEvent.transactionId
      val events =
        listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
          as List<TransactionEvent<Any>>

      val closePaymentResponse =
        ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }

      /* preconditions */
      given(
          transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(
            TRANSACTION_ID))
        .willReturn(events.toFlux())

      given(nodeClient.closePayment(capture(redirectClosePaymentRequestCaptor)))
        .willReturn(Mono.just(closePaymentResponse))

      val fee = authEvent.data.fee
      val amount = authEvent.data.amount
      val totalAmount = amount + fee

      val feeEuroCents = BigDecimal(fee)
      val amountEuroCents = BigDecimal(amount)
      val totalAmountEuroCents = BigDecimal(totalAmount)

      /* test */
      assertEquals(
        closePaymentResponse,
        nodeService.closePayment(TransactionId(transactionId), transactionOutcome))

      val expected =
        RedirectClosePaymentRequestV2Dto().apply {
          outcome = RedirectClosePaymentRequestV2Dto.OutcomeEnum.KO
          this.transactionId = transactionId
          paymentTokens =
            activatedEvent.data.paymentNotices.map { paymentNotice -> paymentNotice.paymentToken }
          transactionDetails =
            TransactionDetailsDto().apply {
              transaction =
                TransactionDto().apply {
                  transactionStatus =
                    TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_DENIED.status
                  this.transactionId = transactionId
                  this.fee = feeEuroCents
                  this.amount = amountEuroCents
                  grandTotal = totalAmountEuroCents
                  this.errorCode =
                    (authCompletedEvent.data.transactionGatewayAuthorizationData
                        as RedirectTransactionGatewayAuthorizationData)
                      .errorCode
                  rrn = authCompletedEvent.data.rrn
                  creationDate = ZonedDateTime.parse(activatedEvent.creationDate).toOffsetDateTime()
                  psp =
                    PspDto().apply {
                      idPsp = authEvent.data.pspId
                      brokerName = authEvent.data.brokerName
                      idChannel = authEvent.data.pspChannelCode
                      businessName = authEvent.data.pspBusinessName
                      pspOnUs = authEvent.data.isPspOnUs
                    }
                  authorizationCode = null
                  timestampOperation = authCompletedEvent.data.timestampOperation
                  paymentGateway = authEvent.data.paymentGateway.name
                }
              user = UserDto().apply { type = UserDto.TypeEnum.REGISTERED }
              info =
                InfoDto().apply {
                  type = authEvent.data.paymentTypeCode
                  clientId = Transaction.ClientId.IO.name
                  brand = authEvent.data.paymentTypeCode
                  brandLogo =
                    (authEvent.data.transactionGatewayAuthorizationRequestedData
                        as RedirectTransactionGatewayAuthorizationRequestedData)
                      .logo
                      .toString()
                  paymentMethodName = authEvent.data.paymentMethodName
                }
            }
          additionalPaymentInformations = null
        }

      assertEquals(expected, redirectClosePaymentRequestCaptor.value)
    }

  @Test
  fun `ClosePaymentRequestV2Dto for close payment KO for authorization KO is correct for REDIRECT payment gateway for registered user`() =
    runTest {
      val transactionOutcome = ClosePaymentOutcome.KO
      val redirectTransactionGatewayAuthorizationRequestedData =
        redirectTransactionGatewayAuthorizationRequestedData()
          as RedirectTransactionGatewayAuthorizationRequestedData
      val redirectTransactionGatewayAuthorizationData =
        redirectTransactionGatewayAuthorizationData(
          RedirectTransactionGatewayAuthorizationData.Outcome.KO, "errorCode")
          as RedirectTransactionGatewayAuthorizationData

      val activatedEvent = transactionActivateEvent()
      activatedEvent.data.clientId = Transaction.ClientId.IO
      val authEvent =
        transactionAuthorizationRequestedEvent(
          TransactionAuthorizationRequestData.PaymentGateway.REDIRECT,
          redirectTransactionGatewayAuthorizationRequestedData)
      val authCompletedEvent =
        transactionAuthorizationCompletedEvent(redirectTransactionGatewayAuthorizationData)
      val closureRequestedEvent = transactionClosureRequestedEvent()
      val closureError = transactionClosureErrorEvent()
      val transactionId = activatedEvent.transactionId
      val events =
        listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
          as List<TransactionEvent<Any>>

      val closePaymentResponse =
        ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }

      /* preconditions */
      given(
          transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(
            TRANSACTION_ID))
        .willReturn(events.toFlux())

      given(nodeClient.closePayment(capture(redirectClosePaymentRequestCaptor)))
        .willReturn(Mono.just(closePaymentResponse))

      val fee = authEvent.data.fee
      val amount = authEvent.data.amount
      val totalAmount = amount + fee

      val feeEuroCents = BigDecimal(fee)
      val amountEuroCents = BigDecimal(amount)
      val totalAmountEuroCents = BigDecimal(totalAmount)

      /* test */
      assertEquals(
        closePaymentResponse,
        nodeService.closePayment(TransactionId(transactionId), transactionOutcome))

      val expected =
        RedirectClosePaymentRequestV2Dto().apply {
          outcome = RedirectClosePaymentRequestV2Dto.OutcomeEnum.KO
          this.transactionId = transactionId
          paymentTokens =
            activatedEvent.data.paymentNotices.map { paymentNotice -> paymentNotice.paymentToken }
          transactionDetails =
            TransactionDetailsDto().apply {
              transaction =
                TransactionDto().apply {
                  transactionStatus =
                    TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_DENIED.status
                  this.transactionId = transactionId
                  this.fee = feeEuroCents
                  this.amount = amountEuroCents
                  grandTotal = totalAmountEuroCents
                  this.errorCode =
                    (authCompletedEvent.data.transactionGatewayAuthorizationData
                        as RedirectTransactionGatewayAuthorizationData)
                      .errorCode
                  rrn = authCompletedEvent.data.rrn
                  creationDate = ZonedDateTime.parse(activatedEvent.creationDate).toOffsetDateTime()
                  psp =
                    PspDto().apply {
                      idPsp = authEvent.data.pspId
                      brokerName = authEvent.data.brokerName
                      idChannel = authEvent.data.pspChannelCode
                      businessName = authEvent.data.pspBusinessName
                      pspOnUs = authEvent.data.isPspOnUs
                    }
                  authorizationCode = null
                  timestampOperation = authCompletedEvent.data.timestampOperation
                  paymentGateway = authEvent.data.paymentGateway.name
                }
              user = UserDto().apply { type = UserDto.TypeEnum.REGISTERED }
              info =
                InfoDto().apply {
                  type = authEvent.data.paymentTypeCode
                  clientId = Transaction.ClientId.IO.name
                  brand = authEvent.data.paymentTypeCode
                  brandLogo =
                    (authEvent.data.transactionGatewayAuthorizationRequestedData
                        as RedirectTransactionGatewayAuthorizationRequestedData)
                      .logo
                      .toString()
                  paymentMethodName = authEvent.data.paymentMethodName
                }
            }
          additionalPaymentInformations = null
        }

      assertEquals(expected, redirectClosePaymentRequestCaptor.value)
    }

  @ParameterizedTest
  @MethodSource("closePaymentDateFormat")
  fun `ClosePaymentRequestV2Dto for close payment OK has additional properties and transaction details valued correctly for PayPal method for registered user`(
    timestampOperation: String,
    expectedLocalDate: String
  ) = runTest {
    val transactionOutcome = ClosePaymentOutcome.OK
    val paypalTransactionGatewayAuthorizationRequestedData =
      NpgTransactionGatewayAuthorizationRequestedData(
        LOGO_URI,
        NpgClient.PaymentMethod.PAYPAL.toString(),
        "npgSessionId",
        "npgConfirmPaymentSessionId",
        null)
    val paypalTransactionGatewayAuthorizationData =
      npgTransactionGatewayAuthorizationData(OperationResultDto.EXECUTED)

    val activatedEvent = transactionActivateEvent()
    activatedEvent.data.clientId = Transaction.ClientId.IO
    val authEvent =
      TransactionAuthorizationRequestedEvent(
        TRANSACTION_ID,
        TransactionAuthorizationRequestData(
          100,
          10,
          "paymentInstrumentId",
          "pspId",
          PaymentCode.PPAL.name,
          "brokerName",
          "pspChannelCode",
          "paymentMethodName",
          "pspBusinessName",
          false,
          AUTHORIZATION_REQUEST_ID,
          TransactionAuthorizationRequestData.PaymentGateway.NPG,
          "paymentMethodDescription",
          paypalTransactionGatewayAuthorizationRequestedData))
    val authCompletedEvent =
      transactionAuthorizationCompletedEvent(paypalTransactionGatewayAuthorizationData)
    val closureRequestedEvent = transactionClosureRequestedEvent()
    val closureError = transactionClosureErrorEvent()
    val transactionId = activatedEvent.transactionId
    val nodoTimestampOperation = OffsetDateTime.parse(timestampOperation)
    authCompletedEvent.data.timestampOperation = nodoTimestampOperation.toString()
    val events =
      listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
        as List<TransactionEvent<Any>>

    val closePaymentResponse =
      ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }
    val userFiscalCode = "userFiscalCode"

    /* preconditions */
    given(confidentialDataUtils.decryptWalletSessionToken(any()))
      .willReturn(mono { userFiscalCode })
    given(
        transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(TRANSACTION_ID))
      .willReturn(events.toFlux())
    given(confidentialDataUtils.eCommerceDecrypt(eq(activatedEvent.data.email), any()))
      .willReturn(Mono.just(Email(EMAIL_STRING)))

    given(nodeClient.closePayment(capture(paypalClosePaymentRequestCaptor)))
      .willReturn(Mono.just(closePaymentResponse))

    val fee = authEvent.data.fee
    val amount = authEvent.data.amount
    val totalAmount = amount + fee

    val feeEuro = EuroUtils.euroCentsToEuro(fee)
    val totalAmountEuro = EuroUtils.euroCentsToEuro(totalAmount)

    val feeEuroCents = BigDecimal(fee)
    val amountEuroCents = BigDecimal(amount)
    val totalAmountEuroCents = BigDecimal(totalAmount)

    /* test */
    assertEquals(
      closePaymentResponse,
      nodeService.closePayment(TransactionId(transactionId), transactionOutcome))
    val expectedTimestamp = expectedLocalDate

    val expected =
      PayPalClosePaymentRequestV2Dto().apply {
        outcome = PayPalClosePaymentRequestV2Dto.OutcomeEnum.OK
        this.transactionId = transactionId
        paymentTokens =
          activatedEvent.data.paymentNotices.map { paymentNotice -> paymentNotice.paymentToken }
        this.timestampOperation = OffsetDateTime.parse(authCompletedEvent.data.timestampOperation)
        this.fee = feeEuro
        idPSP = authEvent.data.pspId
        idChannel = authEvent.data.pspChannelCode
        idBrokerPSP = authEvent.data.brokerName
        paymentMethod = authEvent.data.paymentTypeCode
        this.totalAmount = totalAmountEuro
        transactionDetails =
          TransactionDetailsDto().apply {
            transaction =
              TransactionDto().apply {
                transactionStatus =
                  TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_CONFIRMED.status
                this.transactionId = transactionId
                this.fee = feeEuroCents
                this.amount = amountEuroCents
                grandTotal = totalAmountEuroCents
                this.errorCode = errorCode
                rrn = authCompletedEvent.data.rrn
                creationDate = ZonedDateTime.parse(activatedEvent.creationDate).toOffsetDateTime()
                psp =
                  PspDto().apply {
                    idPsp = authEvent.data.pspId
                    brokerName = authEvent.data.brokerName
                    idChannel = authEvent.data.pspChannelCode
                    businessName = authEvent.data.pspBusinessName
                    pspOnUs = authEvent.data.isPspOnUs
                  }
                authorizationCode = authCompletedEvent.data.authorizationCode
                this.timestampOperation = authCompletedEvent.data.timestampOperation
                paymentGateway = authEvent.data.paymentGateway.name
              }
            user =
              UserDto().apply {
                type = UserDto.TypeEnum.REGISTERED
                fiscalCode = userFiscalCode
              }
            info =
              InfoDto().apply {
                type = authEvent.data.paymentTypeCode
                clientId = Transaction.ClientId.IO.name
                brand = authEvent.data.paymentTypeCode
                brandLogo =
                  (authEvent.data.transactionGatewayAuthorizationRequestedData
                      as NpgTransactionGatewayAuthorizationRequestedData)
                    .logo
                    .toString()
                paymentMethodName = authEvent.data.paymentMethodName
              }
          }
        additionalPaymentInformations =
          PayPalAdditionalPaymentInformationsDto().apply {
            this.transactionId =
              (authCompletedEvent.data.transactionGatewayAuthorizationData
                  as NpgTransactionGatewayAuthorizationData)
                .operationId
            this.pspTransactionId =
              (authCompletedEvent.data.transactionGatewayAuthorizationData
                  as NpgTransactionGatewayAuthorizationData)
                .paymentEndToEndId
            this.timestampOperation = expectedTimestamp
            this.fee = feeEuro.toString()
            this.totalAmount = totalAmountEuro.toString()
            this.email = EMAIL_STRING
          }
      }

    assertEquals(expected, paypalClosePaymentRequestCaptor.value)
  }

  @Test
  fun `ClosePaymentRequestV2Dto for close payment KO for cancelled transaction is correct for PayPal method for registered user`() =
    runTest {
      val transactionOutcome = ClosePaymentOutcome.KO
      val paypalTransactionGatewayAuthorizationRequestedData =
        NpgTransactionGatewayAuthorizationRequestedData(
          LOGO_URI,
          NpgClient.PaymentMethod.PAYPAL.toString(),
          "npgSessionId",
          "npgConfirmPaymentSessionId",
          null)
      val paypalTransactionGatewayAuthorizationData =
        npgTransactionGatewayAuthorizationData(OperationResultDto.DECLINED)

      val activatedEvent = transactionActivateEvent()
      activatedEvent.data.clientId = Transaction.ClientId.IO
      val authEvent =
        TransactionAuthorizationRequestedEvent(
          TRANSACTION_ID,
          TransactionAuthorizationRequestData(
            100,
            10,
            "paymentInstrumentId",
            "pspId",
            PaymentCode.PPAL.name,
            "brokerName",
            "pspChannelCode",
            "paymentMethodName",
            "pspBusinessName",
            false,
            AUTHORIZATION_REQUEST_ID,
            TransactionAuthorizationRequestData.PaymentGateway.NPG,
            "paymentMethodDescription",
            paypalTransactionGatewayAuthorizationRequestedData))
      val authCompletedEvent =
        transactionAuthorizationCompletedEvent(paypalTransactionGatewayAuthorizationData)
      val closureRequestedEvent = transactionClosureRequestedEvent()
      val closureError = transactionClosureErrorEvent()
      val transactionId = activatedEvent.transactionId
      val events =
        listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
          as List<TransactionEvent<Any>>

      val closePaymentResponse =
        ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }

      /* preconditions */
      given(
          transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(
            TRANSACTION_ID))
        .willReturn(events.toFlux())

      given(nodeClient.closePayment(capture(paypalClosePaymentRequestCaptor)))
        .willReturn(Mono.just(closePaymentResponse))

      val fee = authEvent.data.fee
      val amount = authEvent.data.amount
      val totalAmount = amount + fee

      val feeEuroCents = BigDecimal(fee)
      val amountEuroCents = BigDecimal(amount)
      val totalAmountEuroCents = BigDecimal(totalAmount)

      /* test */
      assertEquals(
        closePaymentResponse,
        nodeService.closePayment(TransactionId(transactionId), transactionOutcome))

      val expected =
        PayPalClosePaymentRequestV2Dto().apply {
          outcome = PayPalClosePaymentRequestV2Dto.OutcomeEnum.KO
          this.transactionId = transactionId
          paymentTokens =
            activatedEvent.data.paymentNotices.map { paymentNotice -> paymentNotice.paymentToken }
          transactionDetails =
            TransactionDetailsDto().apply {
              transaction =
                TransactionDto().apply {
                  transactionStatus =
                    TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_DENIED.status
                  this.transactionId = transactionId
                  this.fee = feeEuroCents
                  this.amount = amountEuroCents
                  grandTotal = totalAmountEuroCents
                  this.errorCode =
                    (authCompletedEvent.data.transactionGatewayAuthorizationData
                        as NpgTransactionGatewayAuthorizationData)
                      .errorCode
                  rrn = authCompletedEvent.data.rrn
                  creationDate = ZonedDateTime.parse(activatedEvent.creationDate).toOffsetDateTime()
                  psp =
                    PspDto().apply {
                      idPsp = authEvent.data.pspId
                      brokerName = authEvent.data.brokerName
                      idChannel = authEvent.data.pspChannelCode
                      businessName = authEvent.data.pspBusinessName
                      pspOnUs = authEvent.data.isPspOnUs
                    }
                  authorizationCode = null
                  timestampOperation = authCompletedEvent.data.timestampOperation
                  paymentGateway = authEvent.data.paymentGateway.name
                }
              user = UserDto().apply { type = UserDto.TypeEnum.REGISTERED }
              info =
                InfoDto().apply {
                  type = authEvent.data.paymentTypeCode
                  clientId = Transaction.ClientId.IO.name
                  brand = authEvent.data.paymentTypeCode
                  brandLogo =
                    (authEvent.data.transactionGatewayAuthorizationRequestedData
                        as NpgTransactionGatewayAuthorizationRequestedData)
                      .logo
                      .toString()
                  paymentMethodName = authEvent.data.paymentMethodName
                }
            }
          additionalPaymentInformations = null
        }

      assertEquals(expected, paypalClosePaymentRequestCaptor.value)
    }

  @Test
  fun `ClosePaymentRequestV2Dto for close payment KO for authorization KO is correct for PayPal method for registered user`() =
    runTest {
      val transactionOutcome = ClosePaymentOutcome.KO
      val paypalTransactionGatewayAuthorizationRequestedData =
        NpgTransactionGatewayAuthorizationRequestedData(
          LOGO_URI,
          NpgClient.PaymentMethod.PAYPAL.toString(),
          "npgSessionId",
          "npgConfirmPaymentSessionId",
          null)
      val paypalTransactionGatewayAuthorizationData =
        npgTransactionGatewayAuthorizationData(OperationResultDto.DECLINED)

      val activatedEvent = transactionActivateEvent()
      activatedEvent.data.clientId = Transaction.ClientId.IO
      val authEvent =
        TransactionAuthorizationRequestedEvent(
          TRANSACTION_ID,
          TransactionAuthorizationRequestData(
            100,
            10,
            "paymentInstrumentId",
            "pspId",
            PaymentCode.PPAL.name,
            "brokerName",
            "pspChannelCode",
            "paymentMethodName",
            "pspBusinessName",
            false,
            AUTHORIZATION_REQUEST_ID,
            TransactionAuthorizationRequestData.PaymentGateway.NPG,
            "paymentMethodDescription",
            paypalTransactionGatewayAuthorizationRequestedData))
      val authCompletedEvent =
        transactionAuthorizationCompletedEvent(paypalTransactionGatewayAuthorizationData)
      val closureRequestedEvent = transactionClosureRequestedEvent()
      val closureError = transactionClosureErrorEvent()
      val transactionId = activatedEvent.transactionId
      val events =
        listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
          as List<TransactionEvent<Any>>

      val closePaymentResponse =
        ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }

      /* preconditions */
      given(
          transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(
            TRANSACTION_ID))
        .willReturn(events.toFlux())

      given(nodeClient.closePayment(capture(paypalClosePaymentRequestCaptor)))
        .willReturn(Mono.just(closePaymentResponse))

      val fee = authEvent.data.fee
      val amount = authEvent.data.amount
      val totalAmount = amount + fee

      val feeEuroCents = BigDecimal(fee)
      val amountEuroCents = BigDecimal(amount)
      val totalAmountEuroCents = BigDecimal(totalAmount)

      /* test */
      assertEquals(
        closePaymentResponse,
        nodeService.closePayment(TransactionId(transactionId), transactionOutcome))

      val expected =
        PayPalClosePaymentRequestV2Dto().apply {
          outcome = PayPalClosePaymentRequestV2Dto.OutcomeEnum.KO
          this.transactionId = transactionId
          paymentTokens =
            activatedEvent.data.paymentNotices.map { paymentNotice -> paymentNotice.paymentToken }
          transactionDetails =
            TransactionDetailsDto().apply {
              transaction =
                TransactionDto().apply {
                  transactionStatus =
                    TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_DENIED.status
                  this.transactionId = transactionId
                  this.fee = feeEuroCents
                  this.amount = amountEuroCents
                  grandTotal = totalAmountEuroCents
                  this.errorCode =
                    (authCompletedEvent.data.transactionGatewayAuthorizationData
                        as NpgTransactionGatewayAuthorizationData)
                      .errorCode
                  rrn = authCompletedEvent.data.rrn
                  creationDate = ZonedDateTime.parse(activatedEvent.creationDate).toOffsetDateTime()
                  psp =
                    PspDto().apply {
                      idPsp = authEvent.data.pspId
                      brokerName = authEvent.data.brokerName
                      idChannel = authEvent.data.pspChannelCode
                      businessName = authEvent.data.pspBusinessName
                      pspOnUs = authEvent.data.isPspOnUs
                    }
                  authorizationCode = null
                  timestampOperation = authCompletedEvent.data.timestampOperation
                  paymentGateway = authEvent.data.paymentGateway.name
                }
              user = UserDto().apply { type = UserDto.TypeEnum.REGISTERED }
              info =
                InfoDto().apply {
                  type = authEvent.data.paymentTypeCode
                  clientId = Transaction.ClientId.IO.name
                  brand = authEvent.data.paymentTypeCode
                  brandLogo =
                    (authEvent.data.transactionGatewayAuthorizationRequestedData
                        as NpgTransactionGatewayAuthorizationRequestedData)
                      .logo
                      .toString()
                  paymentMethodName = authEvent.data.paymentMethodName
                }
            }
          additionalPaymentInformations = null
        }

      assertEquals(expected, paypalClosePaymentRequestCaptor.value)
    }

  @ParameterizedTest
  @MethodSource("closePaymentDateFormat")
  fun `ClosePaymentRequestV2Dto for close payment OK has additional properties and transaction details valued correctly for BancomatPay method for registered user`(
    timestampOperation: String,
    expectedLocalDate: String
  ) = runTest {
    val transactionOutcome = ClosePaymentOutcome.OK
    val bancomatPayTransactionGatewayAuthorizationRequestedData =
      NpgTransactionGatewayAuthorizationRequestedData(
        LOGO_URI,
        NpgClient.PaymentMethod.BANCOMATPAY.toString(),
        "npgSessionId",
        "npgConfirmPaymentSessionId",
        null)
    val bancomatPayTransactionGatewayAuthorizationData =
      npgTransactionGatewayAuthorizationData(OperationResultDto.EXECUTED)

    val activatedEvent = transactionActivateEvent()
    activatedEvent.data.clientId = Transaction.ClientId.IO
    val authEvent =
      TransactionAuthorizationRequestedEvent(
        TRANSACTION_ID,
        TransactionAuthorizationRequestData(
          100,
          10,
          "paymentInstrumentId",
          "pspId",
          PaymentCode.BPAY.name,
          "brokerName",
          "pspChannelCode",
          "paymentMethodName",
          "pspBusinessName",
          false,
          AUTHORIZATION_REQUEST_ID,
          TransactionAuthorizationRequestData.PaymentGateway.NPG,
          "paymentMethodDescription",
          bancomatPayTransactionGatewayAuthorizationRequestedData))
    val authCompletedEvent =
      transactionAuthorizationCompletedEvent(bancomatPayTransactionGatewayAuthorizationData)
    val closureRequestedEvent = transactionClosureRequestedEvent()
    val closureError = transactionClosureErrorEvent()
    val transactionId = activatedEvent.transactionId
    val nodoTimestampOperation = OffsetDateTime.parse(timestampOperation)
    authCompletedEvent.data.timestampOperation = nodoTimestampOperation.toString()
    val events =
      listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
        as List<TransactionEvent<Any>>

    val closePaymentResponse =
      ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }

    val userFiscalCode = "userFiscalCode"

    /* preconditions */
    given(confidentialDataUtils.decryptWalletSessionToken(any()))
      .willReturn(mono { userFiscalCode })
    given(
        transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(TRANSACTION_ID))
      .willReturn(events.toFlux())
    given(confidentialDataUtils.eCommerceDecrypt(eq(activatedEvent.data.email), any()))
      .willReturn(Mono.just(Email(EMAIL_STRING)))

    given(nodeClient.closePayment(capture(bancomatPayClosePaymentRequestCaptor)))
      .willReturn(Mono.just(closePaymentResponse))

    val fee = authEvent.data.fee
    val amount = authEvent.data.amount
    val totalAmount = amount + fee

    val feeEuro = EuroUtils.euroCentsToEuro(fee)
    val totalAmountEuro = EuroUtils.euroCentsToEuro(totalAmount)

    val feeEuroCents = BigDecimal(fee)
    val amountEuroCents = BigDecimal(amount)
    val totalAmountEuroCents = BigDecimal(totalAmount)

    /* test */
    assertEquals(
      closePaymentResponse,
      nodeService.closePayment(TransactionId(transactionId), transactionOutcome))
    val expectedTimestamp = expectedLocalDate

    val expected =
      BancomatPayClosePaymentRequestV2Dto().apply {
        outcome = BancomatPayClosePaymentRequestV2Dto.OutcomeEnum.OK
        this.transactionId = transactionId
        paymentTokens =
          activatedEvent.data.paymentNotices.map { paymentNotice -> paymentNotice.paymentToken }
        this.timestampOperation = OffsetDateTime.parse(authCompletedEvent.data.timestampOperation)
        this.fee = feeEuro
        idPSP = authEvent.data.pspId
        idChannel = authEvent.data.pspChannelCode
        idBrokerPSP = authEvent.data.brokerName
        paymentMethod = authEvent.data.paymentTypeCode
        this.totalAmount = totalAmountEuro
        transactionDetails =
          TransactionDetailsDto().apply {
            transaction =
              TransactionDto().apply {
                transactionStatus =
                  TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_CONFIRMED.status
                this.transactionId = transactionId
                this.fee = feeEuroCents
                this.amount = amountEuroCents
                grandTotal = totalAmountEuroCents
                this.errorCode = errorCode
                rrn = authCompletedEvent.data.rrn
                creationDate = ZonedDateTime.parse(activatedEvent.creationDate).toOffsetDateTime()
                psp =
                  PspDto().apply {
                    idPsp = authEvent.data.pspId
                    brokerName = authEvent.data.brokerName
                    idChannel = authEvent.data.pspChannelCode
                    businessName = authEvent.data.pspBusinessName
                    pspOnUs = authEvent.data.isPspOnUs
                  }
                authorizationCode = authCompletedEvent.data.authorizationCode
                this.timestampOperation = authCompletedEvent.data.timestampOperation
                paymentGateway = authEvent.data.paymentGateway.name
              }
            user =
              UserDto().apply {
                type = UserDto.TypeEnum.REGISTERED
                fiscalCode = userFiscalCode
              }
            info =
              InfoDto().apply {
                type = authEvent.data.paymentTypeCode
                clientId = Transaction.ClientId.IO.name
                brand = authEvent.data.paymentTypeCode
                brandLogo =
                  (authEvent.data.transactionGatewayAuthorizationRequestedData
                      as NpgTransactionGatewayAuthorizationRequestedData)
                    .logo
                    .toString()
                paymentMethodName = authEvent.data.paymentMethodName
              }
          }
        additionalPaymentInformations =
          BancomatPayAdditionalPaymentInformationsDto().apply {
            this.transactionId =
              (authCompletedEvent.data.transactionGatewayAuthorizationData
                  as NpgTransactionGatewayAuthorizationData)
                .paymentEndToEndId
            this.outcomePaymentGateway =
              BancomatPayAdditionalPaymentInformationsDto.OutcomePaymentGatewayEnum.OK
            this.totalAmount = totalAmountEuro.toString()
            this.fee = feeEuro.toString()
            this.timestampOperation = expectedTimestamp
            this.authorizationCode =
              (authCompletedEvent.data.transactionGatewayAuthorizationData
                  as NpgTransactionGatewayAuthorizationData)
                .operationId
            this.email = EMAIL_STRING
          }
      }

    assertEquals(expected, bancomatPayClosePaymentRequestCaptor.value)
  }

  @Test
  fun `ClosePaymentRequestV2Dto for close payment KO for cancelled transaction is correct for BancomatPay method for registered user`() =
    runTest {
      val transactionOutcome = ClosePaymentOutcome.KO
      val bancomatPayTransactionGatewayAuthorizationRequestedData =
        NpgTransactionGatewayAuthorizationRequestedData(
          LOGO_URI,
          NpgClient.PaymentMethod.BANCOMATPAY.toString(),
          "npgSessionId",
          "npgConfirmPaymentSessionId",
          null)
      val bancomatPayTransactionGatewayAuthorizationData =
        npgTransactionGatewayAuthorizationData(OperationResultDto.DECLINED)

      val activatedEvent = transactionActivateEvent()
      activatedEvent.data.clientId = Transaction.ClientId.IO
      val authEvent =
        TransactionAuthorizationRequestedEvent(
          TRANSACTION_ID,
          TransactionAuthorizationRequestData(
            100,
            10,
            "paymentInstrumentId",
            "pspId",
            PaymentCode.BPAY.name,
            "brokerName",
            "pspChannelCode",
            "paymentMethodName",
            "pspBusinessName",
            false,
            AUTHORIZATION_REQUEST_ID,
            TransactionAuthorizationRequestData.PaymentGateway.NPG,
            "paymentMethodDescription",
            bancomatPayTransactionGatewayAuthorizationRequestedData))
      val authCompletedEvent =
        transactionAuthorizationCompletedEvent(bancomatPayTransactionGatewayAuthorizationData)
      val closureRequestedEvent = transactionClosureRequestedEvent()
      val closureError = transactionClosureErrorEvent()
      val transactionId = activatedEvent.transactionId
      val events =
        listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
          as List<TransactionEvent<Any>>

      val closePaymentResponse =
        ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }

      /* preconditions */
      given(
          transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(
            TRANSACTION_ID))
        .willReturn(events.toFlux())

      given(nodeClient.closePayment(capture(bancomatPayClosePaymentRequestCaptor)))
        .willReturn(Mono.just(closePaymentResponse))

      val fee = authEvent.data.fee
      val amount = authEvent.data.amount
      val totalAmount = amount + fee

      val feeEuroCents = BigDecimal(fee)
      val amountEuroCents = BigDecimal(amount)
      val totalAmountEuroCents = BigDecimal(totalAmount)

      /* test */
      assertEquals(
        closePaymentResponse,
        nodeService.closePayment(TransactionId(transactionId), transactionOutcome))

      val expected =
        BancomatPayClosePaymentRequestV2Dto().apply {
          outcome = BancomatPayClosePaymentRequestV2Dto.OutcomeEnum.KO
          this.transactionId = transactionId
          paymentTokens =
            activatedEvent.data.paymentNotices.map { paymentNotice -> paymentNotice.paymentToken }
          transactionDetails =
            TransactionDetailsDto().apply {
              transaction =
                TransactionDto().apply {
                  transactionStatus =
                    TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_DENIED.status
                  this.transactionId = transactionId
                  this.fee = feeEuroCents
                  this.amount = amountEuroCents
                  grandTotal = totalAmountEuroCents
                  this.errorCode =
                    (authCompletedEvent.data.transactionGatewayAuthorizationData
                        as NpgTransactionGatewayAuthorizationData)
                      .errorCode
                  rrn = authCompletedEvent.data.rrn
                  creationDate = ZonedDateTime.parse(activatedEvent.creationDate).toOffsetDateTime()
                  psp =
                    PspDto().apply {
                      idPsp = authEvent.data.pspId
                      brokerName = authEvent.data.brokerName
                      idChannel = authEvent.data.pspChannelCode
                      businessName = authEvent.data.pspBusinessName
                      pspOnUs = authEvent.data.isPspOnUs
                    }
                  authorizationCode = null
                  timestampOperation = authCompletedEvent.data.timestampOperation
                  paymentGateway = authEvent.data.paymentGateway.name
                }
              user = UserDto().apply { type = UserDto.TypeEnum.REGISTERED }
              info =
                InfoDto().apply {
                  type = authEvent.data.paymentTypeCode
                  clientId = Transaction.ClientId.IO.name
                  brand = authEvent.data.paymentTypeCode
                  brandLogo =
                    (authEvent.data.transactionGatewayAuthorizationRequestedData
                        as NpgTransactionGatewayAuthorizationRequestedData)
                      .logo
                      .toString()
                  paymentMethodName = authEvent.data.paymentMethodName
                }
            }
          additionalPaymentInformations = null
        }

      assertEquals(expected, bancomatPayClosePaymentRequestCaptor.value)
    }

  @Test
  fun `ClosePaymentRequestV2Dto for close payment KO for authorization KO is correct for BancomatPay method for registered user`() =
    runTest {
      val transactionOutcome = ClosePaymentOutcome.KO
      val bancomatPayTransactionGatewayAuthorizationRequestedData =
        NpgTransactionGatewayAuthorizationRequestedData(
          LOGO_URI,
          NpgClient.PaymentMethod.BANCOMATPAY.toString(),
          "npgSessionId",
          "npgConfirmPaymentSessionId",
          null)
      val bancomatPayTransactionGatewayAuthorizationData =
        npgTransactionGatewayAuthorizationData(OperationResultDto.DECLINED)

      val activatedEvent = transactionActivateEvent()
      activatedEvent.data.clientId = Transaction.ClientId.IO
      val authEvent =
        TransactionAuthorizationRequestedEvent(
          TRANSACTION_ID,
          TransactionAuthorizationRequestData(
            100,
            10,
            "paymentInstrumentId",
            "pspId",
            PaymentCode.BPAY.name,
            "brokerName",
            "pspChannelCode",
            "paymentMethodName",
            "pspBusinessName",
            false,
            AUTHORIZATION_REQUEST_ID,
            TransactionAuthorizationRequestData.PaymentGateway.NPG,
            "paymentMethodDescription",
            bancomatPayTransactionGatewayAuthorizationRequestedData))
      val authCompletedEvent =
        transactionAuthorizationCompletedEvent(bancomatPayTransactionGatewayAuthorizationData)
      val closureRequestedEvent = transactionClosureRequestedEvent()
      val closureError = transactionClosureErrorEvent()
      val transactionId = activatedEvent.transactionId
      val events =
        listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
          as List<TransactionEvent<Any>>

      val closePaymentResponse =
        ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }

      /* preconditions */
      given(
          transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(
            TRANSACTION_ID))
        .willReturn(events.toFlux())

      given(nodeClient.closePayment(capture(bancomatPayClosePaymentRequestCaptor)))
        .willReturn(Mono.just(closePaymentResponse))

      val fee = authEvent.data.fee
      val amount = authEvent.data.amount
      val totalAmount = amount + fee

      val feeEuroCents = BigDecimal(fee)
      val amountEuroCents = BigDecimal(amount)
      val totalAmountEuroCents = BigDecimal(totalAmount)

      /* test */
      assertEquals(
        closePaymentResponse,
        nodeService.closePayment(TransactionId(transactionId), transactionOutcome))

      val expected =
        BancomatPayClosePaymentRequestV2Dto().apply {
          outcome = BancomatPayClosePaymentRequestV2Dto.OutcomeEnum.KO
          this.transactionId = transactionId
          paymentTokens =
            activatedEvent.data.paymentNotices.map { paymentNotice -> paymentNotice.paymentToken }
          transactionDetails =
            TransactionDetailsDto().apply {
              transaction =
                TransactionDto().apply {
                  transactionStatus =
                    TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_DENIED.status
                  this.transactionId = transactionId
                  this.fee = feeEuroCents
                  this.amount = amountEuroCents
                  grandTotal = totalAmountEuroCents
                  this.errorCode =
                    (authCompletedEvent.data.transactionGatewayAuthorizationData
                        as NpgTransactionGatewayAuthorizationData)
                      .errorCode
                  rrn = authCompletedEvent.data.rrn
                  creationDate = ZonedDateTime.parse(activatedEvent.creationDate).toOffsetDateTime()
                  psp =
                    PspDto().apply {
                      idPsp = authEvent.data.pspId
                      brokerName = authEvent.data.brokerName
                      idChannel = authEvent.data.pspChannelCode
                      businessName = authEvent.data.pspBusinessName
                      pspOnUs = authEvent.data.isPspOnUs
                    }
                  authorizationCode = null
                  timestampOperation = authCompletedEvent.data.timestampOperation
                  paymentGateway = authEvent.data.paymentGateway.name
                }
              user = UserDto().apply { type = UserDto.TypeEnum.REGISTERED }
              info =
                InfoDto().apply {
                  type = authEvent.data.paymentTypeCode
                  clientId = Transaction.ClientId.IO.name
                  brand = authEvent.data.paymentTypeCode
                  brandLogo =
                    (authEvent.data.transactionGatewayAuthorizationRequestedData
                        as NpgTransactionGatewayAuthorizationRequestedData)
                      .logo
                      .toString()
                  paymentMethodName = authEvent.data.paymentMethodName
                }
            }
          additionalPaymentInformations = null
        }

      assertEquals(expected, bancomatPayClosePaymentRequestCaptor.value)
    }

  @ParameterizedTest
  @MethodSource("closePaymentDateFormat")
  fun `ClosePaymentRequestV2Dto for close payment OK has additional properties and transaction details valued correctly for MyBank method for registered user`(
    timestampOperation: String,
    expectedLocalDate: String
  ) = runTest {
    val transactionOutcome = ClosePaymentOutcome.OK
    val myBankTransactionGatewayAuthorizationRequestedData =
      NpgTransactionGatewayAuthorizationRequestedData(
        LOGO_URI,
        NpgClient.PaymentMethod.MYBANK.toString(),
        "npgSessionId",
        "npgConfirmPaymentSessionId",
        null)
    val myBankTransactionGatewayAuthorizationData =
      npgTransactionGatewayAuthorizationData(OperationResultDto.EXECUTED)

    val activatedEvent = transactionActivateEvent()
    activatedEvent.data.clientId = Transaction.ClientId.IO
    val authEvent =
      TransactionAuthorizationRequestedEvent(
        TRANSACTION_ID,
        TransactionAuthorizationRequestData(
          100,
          10,
          "paymentInstrumentId",
          "pspId",
          PaymentCode.MYBK.name,
          "brokerName",
          "pspChannelCode",
          "paymentMethodName",
          "pspBusinessName",
          false,
          AUTHORIZATION_REQUEST_ID,
          TransactionAuthorizationRequestData.PaymentGateway.NPG,
          "paymentMethodDescription",
          myBankTransactionGatewayAuthorizationRequestedData))
    val authCompletedEvent =
      transactionAuthorizationCompletedEvent(myBankTransactionGatewayAuthorizationData)
    val closureRequestedEvent = transactionClosureRequestedEvent()
    val closureError = transactionClosureErrorEvent()
    val transactionId = activatedEvent.transactionId
    val nodoTimestampOperation = OffsetDateTime.parse(timestampOperation)
    authCompletedEvent.data.timestampOperation = nodoTimestampOperation.toString()
    val events =
      listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
        as List<TransactionEvent<Any>>

    val closePaymentResponse =
      ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }

    val userFiscalCode = "userFiscalCode"

    /* preconditions */
    given(confidentialDataUtils.decryptWalletSessionToken(any()))
      .willReturn(mono { userFiscalCode })
    given(
        transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(TRANSACTION_ID))
      .willReturn(events.toFlux())
    given(confidentialDataUtils.eCommerceDecrypt(eq(activatedEvent.data.email), any()))
      .willReturn(Mono.just(Email(EMAIL_STRING)))

    given(nodeClient.closePayment(capture(myBankClosePaymentRequestCaptor)))
      .willReturn(Mono.just(closePaymentResponse))
    val fee = authEvent.data.fee
    val amount = authEvent.data.amount
    val totalAmount = amount + fee

    val feeEuro = EuroUtils.euroCentsToEuro(fee)
    val totalAmountEuro = EuroUtils.euroCentsToEuro(totalAmount)

    val feeEuroCents = BigDecimal(fee)
    val amountEuroCents = BigDecimal(amount)
    val totalAmountEuroCents = BigDecimal(totalAmount)

    /* test */
    assertEquals(
      closePaymentResponse,
      nodeService.closePayment(TransactionId(transactionId), transactionOutcome))
    val expectedTimestamp = expectedLocalDate

    val expected =
      MyBankClosePaymentRequestV2Dto().apply {
        outcome = MyBankClosePaymentRequestV2Dto.OutcomeEnum.OK
        this.transactionId = transactionId
        paymentTokens =
          activatedEvent.data.paymentNotices.map { paymentNotice -> paymentNotice.paymentToken }
        this.timestampOperation = OffsetDateTime.parse(authCompletedEvent.data.timestampOperation)
        this.fee = feeEuro
        idPSP = authEvent.data.pspId
        idChannel = authEvent.data.pspChannelCode
        idBrokerPSP = authEvent.data.brokerName
        paymentMethod = authEvent.data.paymentTypeCode
        this.totalAmount = totalAmountEuro
        transactionDetails =
          TransactionDetailsDto().apply {
            transaction =
              TransactionDto().apply {
                transactionStatus =
                  TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_CONFIRMED.status
                this.transactionId = transactionId
                this.fee = feeEuroCents
                this.amount = amountEuroCents
                grandTotal = totalAmountEuroCents
                this.errorCode = errorCode
                rrn = authCompletedEvent.data.rrn
                creationDate = ZonedDateTime.parse(activatedEvent.creationDate).toOffsetDateTime()
                psp =
                  PspDto().apply {
                    idPsp = authEvent.data.pspId
                    brokerName = authEvent.data.brokerName
                    idChannel = authEvent.data.pspChannelCode
                    businessName = authEvent.data.pspBusinessName
                    pspOnUs = authEvent.data.isPspOnUs
                  }
                authorizationCode = authCompletedEvent.data.authorizationCode
                this.timestampOperation = authCompletedEvent.data.timestampOperation
                paymentGateway = authEvent.data.paymentGateway.name
              }
            user =
              UserDto().apply {
                type = UserDto.TypeEnum.REGISTERED
                fiscalCode = userFiscalCode
              }
            info =
              InfoDto().apply {
                type = authEvent.data.paymentTypeCode
                clientId = Transaction.ClientId.IO.name
                brand = authEvent.data.paymentTypeCode
                brandLogo =
                  (authEvent.data.transactionGatewayAuthorizationRequestedData
                      as NpgTransactionGatewayAuthorizationRequestedData)
                    .logo
                    .toString()
                paymentMethodName = authEvent.data.paymentMethodName
              }
          }
        additionalPaymentInformations =
          MyBankAdditionalPaymentInformationsDto().apply {
            this.transactionId = authCompletedEvent.transactionId
            this.myBankTransactionId =
              (authCompletedEvent.data.transactionGatewayAuthorizationData
                  as NpgTransactionGatewayAuthorizationData)
                .paymentEndToEndId
            this.totalAmount = totalAmountEuro.toString()
            this.fee = feeEuro.toString()
            this.validationServiceId = NPG_VALIDATION_SERVICE_ID
            this.timestampOperation = expectedTimestamp
            this.email = EMAIL_STRING
          }
      }

    assertEquals(expected, myBankClosePaymentRequestCaptor.value)
  }

  @Test
  fun `ClosePaymentRequestV2Dto for close payment KO for cancelled transaction is correct for MyBank method for registered user`() =
    runTest {
      val transactionOutcome = ClosePaymentOutcome.KO
      val myBankTransactionGatewayAuthorizationRequestedData =
        NpgTransactionGatewayAuthorizationRequestedData(
          LOGO_URI,
          NpgClient.PaymentMethod.MYBANK.toString(),
          "npgSessionId",
          "npgConfirmPaymentSessionId",
          null)
      val myBankTransactionGatewayAuthorizationData =
        npgTransactionGatewayAuthorizationData(OperationResultDto.DECLINED)

      val activatedEvent = transactionActivateEvent()
      activatedEvent.data.clientId = Transaction.ClientId.IO
      val authEvent =
        TransactionAuthorizationRequestedEvent(
          TRANSACTION_ID,
          TransactionAuthorizationRequestData(
            100,
            10,
            "paymentInstrumentId",
            "pspId",
            PaymentCode.MYBK.name,
            "brokerName",
            "pspChannelCode",
            "paymentMethodName",
            "pspBusinessName",
            false,
            AUTHORIZATION_REQUEST_ID,
            TransactionAuthorizationRequestData.PaymentGateway.NPG,
            "paymentMethodDescription",
            myBankTransactionGatewayAuthorizationRequestedData))
      val authCompletedEvent =
        transactionAuthorizationCompletedEvent(myBankTransactionGatewayAuthorizationData)
      val closureRequestedEvent = transactionClosureRequestedEvent()
      val closureError = transactionClosureErrorEvent()
      val transactionId = activatedEvent.transactionId
      val events =
        listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
          as List<TransactionEvent<Any>>

      val closePaymentResponse =
        ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }

      /* preconditions */
      given(
          transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(
            TRANSACTION_ID))
        .willReturn(events.toFlux())

      given(nodeClient.closePayment(capture(bancomatPayClosePaymentRequestCaptor)))
        .willReturn(Mono.just(closePaymentResponse))

      val fee = authEvent.data.fee
      val amount = authEvent.data.amount
      val totalAmount = amount + fee

      val feeEuroCents = BigDecimal(fee)
      val amountEuroCents = BigDecimal(amount)
      val totalAmountEuroCents = BigDecimal(totalAmount)

      /* test */
      assertEquals(
        closePaymentResponse,
        nodeService.closePayment(TransactionId(transactionId), transactionOutcome))

      val expected =
        MyBankClosePaymentRequestV2Dto().apply {
          outcome = MyBankClosePaymentRequestV2Dto.OutcomeEnum.KO
          this.transactionId = transactionId
          paymentTokens =
            activatedEvent.data.paymentNotices.map { paymentNotice -> paymentNotice.paymentToken }
          transactionDetails =
            TransactionDetailsDto().apply {
              transaction =
                TransactionDto().apply {
                  transactionStatus =
                    TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_DENIED.status
                  this.transactionId = transactionId
                  this.fee = feeEuroCents
                  this.amount = amountEuroCents
                  grandTotal = totalAmountEuroCents
                  this.errorCode =
                    (authCompletedEvent.data.transactionGatewayAuthorizationData
                        as NpgTransactionGatewayAuthorizationData)
                      .errorCode
                  rrn = authCompletedEvent.data.rrn
                  creationDate = ZonedDateTime.parse(activatedEvent.creationDate).toOffsetDateTime()
                  psp =
                    PspDto().apply {
                      idPsp = authEvent.data.pspId
                      brokerName = authEvent.data.brokerName
                      idChannel = authEvent.data.pspChannelCode
                      businessName = authEvent.data.pspBusinessName
                      pspOnUs = authEvent.data.isPspOnUs
                    }
                  authorizationCode = null
                  timestampOperation = authCompletedEvent.data.timestampOperation
                  paymentGateway = authEvent.data.paymentGateway.name
                }
              user = UserDto().apply { type = UserDto.TypeEnum.REGISTERED }
              info =
                InfoDto().apply {
                  type = authEvent.data.paymentTypeCode
                  clientId = Transaction.ClientId.IO.name
                  brand = authEvent.data.paymentTypeCode
                  brandLogo =
                    (authEvent.data.transactionGatewayAuthorizationRequestedData
                        as NpgTransactionGatewayAuthorizationRequestedData)
                      .logo
                      .toString()
                  paymentMethodName = authEvent.data.paymentMethodName
                }
            }
          additionalPaymentInformations = null
        }

      assertEquals(expected, bancomatPayClosePaymentRequestCaptor.value)
    }

  @Test
  fun `ClosePaymentRequestV2Dto for close payment KO for authorization KO is correct for MyBank method for registered user`() =
    runTest {
      val transactionOutcome = ClosePaymentOutcome.KO
      val myBankTransactionGatewayAuthorizationRequestedData =
        NpgTransactionGatewayAuthorizationRequestedData(
          LOGO_URI,
          NpgClient.PaymentMethod.MYBANK.toString(),
          "npgSessionId",
          "npgConfirmPaymentSessionId",
          null)
      val myBankTransactionGatewayAuthorizationData =
        npgTransactionGatewayAuthorizationData(OperationResultDto.DECLINED)

      val activatedEvent = transactionActivateEvent()
      activatedEvent.data.clientId = Transaction.ClientId.IO
      val authEvent =
        TransactionAuthorizationRequestedEvent(
          TRANSACTION_ID,
          TransactionAuthorizationRequestData(
            100,
            10,
            "paymentInstrumentId",
            "pspId",
            PaymentCode.MYBK.name,
            "brokerName",
            "pspChannelCode",
            "paymentMethodName",
            "pspBusinessName",
            false,
            AUTHORIZATION_REQUEST_ID,
            TransactionAuthorizationRequestData.PaymentGateway.NPG,
            "paymentMethodDescription",
            myBankTransactionGatewayAuthorizationRequestedData))
      val authCompletedEvent =
        transactionAuthorizationCompletedEvent(myBankTransactionGatewayAuthorizationData)
      val closureRequestedEvent = transactionClosureRequestedEvent()
      val closureError = transactionClosureErrorEvent()
      val transactionId = activatedEvent.transactionId
      val events =
        listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
          as List<TransactionEvent<Any>>

      val closePaymentResponse =
        ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }

      /* preconditions */
      given(
          transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(
            TRANSACTION_ID))
        .willReturn(events.toFlux())

      given(nodeClient.closePayment(capture(bancomatPayClosePaymentRequestCaptor)))
        .willReturn(Mono.just(closePaymentResponse))

      val fee = authEvent.data.fee
      val amount = authEvent.data.amount
      val totalAmount = amount + fee

      val feeEuroCents = BigDecimal(fee)
      val amountEuroCents = BigDecimal(amount)
      val totalAmountEuroCents = BigDecimal(totalAmount)

      /* test */
      assertEquals(
        closePaymentResponse,
        nodeService.closePayment(TransactionId(transactionId), transactionOutcome))

      val expected =
        MyBankClosePaymentRequestV2Dto().apply {
          outcome = MyBankClosePaymentRequestV2Dto.OutcomeEnum.KO
          this.transactionId = transactionId
          paymentTokens =
            activatedEvent.data.paymentNotices.map { paymentNotice -> paymentNotice.paymentToken }
          transactionDetails =
            TransactionDetailsDto().apply {
              transaction =
                TransactionDto().apply {
                  transactionStatus =
                    TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_DENIED.status
                  this.transactionId = transactionId
                  this.fee = feeEuroCents
                  this.amount = amountEuroCents
                  grandTotal = totalAmountEuroCents
                  this.errorCode =
                    (authCompletedEvent.data.transactionGatewayAuthorizationData
                        as NpgTransactionGatewayAuthorizationData)
                      .errorCode
                  rrn = authCompletedEvent.data.rrn
                  creationDate = ZonedDateTime.parse(activatedEvent.creationDate).toOffsetDateTime()
                  psp =
                    PspDto().apply {
                      idPsp = authEvent.data.pspId
                      brokerName = authEvent.data.brokerName
                      idChannel = authEvent.data.pspChannelCode
                      businessName = authEvent.data.pspBusinessName
                      pspOnUs = authEvent.data.isPspOnUs
                    }
                  authorizationCode = null
                  timestampOperation = authCompletedEvent.data.timestampOperation
                  paymentGateway = authEvent.data.paymentGateway.name
                }
              user = UserDto().apply { type = UserDto.TypeEnum.REGISTERED }
              info =
                InfoDto().apply {
                  type = authEvent.data.paymentTypeCode
                  clientId = Transaction.ClientId.IO.name
                  brand = authEvent.data.paymentTypeCode
                  brandLogo =
                    (authEvent.data.transactionGatewayAuthorizationRequestedData
                        as NpgTransactionGatewayAuthorizationRequestedData)
                      .logo
                      .toString()
                  paymentMethodName = authEvent.data.paymentMethodName
                }
            }
          additionalPaymentInformations = null
        }

      assertEquals(expected, bancomatPayClosePaymentRequestCaptor.value)
    }

  @ParameterizedTest
  @MethodSource("closePaymentDateFormat")
  fun `ClosePaymentRequestV2Dto for close payment OK has additional properties and transaction details valued correctly for card wallet for registered user`(
    timestampOperation: String,
    expectedLocalDate: String
  ) = runTest {
    val transactionOutcome = ClosePaymentOutcome.OK
    val authRequestedData =
      NpgTransactionGatewayAuthorizationRequestedData(
        LOGO_URI,
        NpgClient.PaymentMethod.CARDS.toString(),
        "npgSessionId",
        "npgConfirmPaymentSessionId",
        cardsWalletInfo())
    val authData = npgTransactionGatewayAuthorizationData(OperationResultDto.EXECUTED)

    val activatedEvent = transactionActivateEvent()
    activatedEvent.data.clientId = Transaction.ClientId.IO
    val authEvent =
      TransactionAuthorizationRequestedEvent(
        TRANSACTION_ID,
        TransactionAuthorizationRequestData(
          100,
          10,
          "paymentInstrumentId",
          "pspId",
          PaymentCode.CP.name,
          "brokerName",
          "pspChannelCode",
          "paymentMethodName",
          "pspBusinessName",
          false,
          AUTHORIZATION_REQUEST_ID,
          TransactionAuthorizationRequestData.PaymentGateway.NPG,
          "paymentMethodDescription",
          authRequestedData))
    val authCompletedEvent = transactionAuthorizationCompletedEvent(authData)
    val closureRequestedEvent = transactionClosureRequestedEvent()
    val closureError = transactionClosureErrorEvent()
    val transactionId = activatedEvent.transactionId
    val nodoTimestampOperation = OffsetDateTime.parse(timestampOperation)
    authCompletedEvent.data.timestampOperation = nodoTimestampOperation.toString()
    val events =
      listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
        as List<TransactionEvent<Any>>

    val closePaymentResponse =
      ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }
    val userFiscalCode = "userFiscalCode"

    /* preconditions */
    given(confidentialDataUtils.decryptWalletSessionToken(any()))
      .willReturn(mono { userFiscalCode })
    given(
        transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(TRANSACTION_ID))
      .willReturn(events.toFlux())
    given(confidentialDataUtils.eCommerceDecrypt(eq(activatedEvent.data.email), any()))
      .willReturn(Mono.just(Email(EMAIL_STRING)))

    given(nodeClient.closePayment(capture(closePaymentRequestCaptor)))
      .willReturn(Mono.just(closePaymentResponse))

    val fee = authEvent.data.fee
    val amount = authEvent.data.amount
    val totalAmount = amount + fee

    val feeEuro = EuroUtils.euroCentsToEuro(fee)
    val totalAmountEuro = EuroUtils.euroCentsToEuro(totalAmount)

    val feeEuroCents = BigDecimal(fee)
    val amountEuroCents = BigDecimal(amount)
    val totalAmountEuroCents = BigDecimal(totalAmount)

    /* test */
    assertEquals(
      closePaymentResponse,
      nodeService.closePayment(TransactionId(transactionId), transactionOutcome))
    val expectedTimestamp = expectedLocalDate

    val expected =
      CardClosePaymentRequestV2Dto().apply {
        outcome = CardClosePaymentRequestV2Dto.OutcomeEnum.OK
        this.transactionId = transactionId
        paymentTokens =
          activatedEvent.data.paymentNotices.map { paymentNotice -> paymentNotice.paymentToken }
        this.timestampOperation = OffsetDateTime.parse(authCompletedEvent.data.timestampOperation)
        this.fee = feeEuro
        idPSP = authEvent.data.pspId
        idChannel = authEvent.data.pspChannelCode
        idBrokerPSP = authEvent.data.brokerName
        paymentMethod = authEvent.data.paymentTypeCode
        this.totalAmount = totalAmountEuro
        transactionDetails =
          TransactionDetailsDto().apply {
            transaction =
              TransactionDto().apply {
                transactionStatus =
                  TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_CONFIRMED.status
                this.transactionId = transactionId
                this.fee = feeEuroCents
                this.amount = amountEuroCents
                grandTotal = totalAmountEuroCents
                this.errorCode = errorCode
                rrn = authCompletedEvent.data.rrn
                creationDate = ZonedDateTime.parse(activatedEvent.creationDate).toOffsetDateTime()
                psp =
                  PspDto().apply {
                    idPsp = authEvent.data.pspId
                    brokerName = authEvent.data.brokerName
                    idChannel = authEvent.data.pspChannelCode
                    businessName = authEvent.data.pspBusinessName
                    pspOnUs = authEvent.data.isPspOnUs
                  }
                authorizationCode = authCompletedEvent.data.authorizationCode
                this.timestampOperation = authCompletedEvent.data.timestampOperation
                paymentGateway = authEvent.data.paymentGateway.name
              }
            user =
              UserDto().apply {
                type = UserDto.TypeEnum.REGISTERED
                fiscalCode = userFiscalCode
              }
            info =
              InfoDto().apply {
                type = authEvent.data.paymentTypeCode
                clientId = Transaction.ClientId.IO.name
                brand = authEvent.data.paymentTypeCode
                brandLogo =
                  (authEvent.data.transactionGatewayAuthorizationRequestedData
                      as NpgTransactionGatewayAuthorizationRequestedData)
                    .logo
                    .toString()
                paymentMethodName = authEvent.data.paymentMethodName
                bin = NPG_WALLET_CARD_BIN
                lastFourDigits = NPG_WALLET_CARD_LAST_FOUR_DIGITS
              }
          }
        additionalPaymentInformations =
          CardAdditionalPaymentInformationsDto().apply {
            this.authorizationCode = authCompletedEvent.data.authorizationCode
            this.fee = feeEuro.toString()
            this.outcomePaymentGateway = OutcomePaymentGatewayEnum.OK
            this.rrn = authCompletedEvent.data.rrn
            this.timestampOperation = expectedTimestamp
            this.fee = feeEuro.toString()
            this.totalAmount = totalAmountEuro.toString()
            this.email = EMAIL_STRING
          }
      }

    assertEquals(expected, closePaymentRequestCaptor.value)
  }

  @Test
  fun `ClosePaymentRequestV2Dto for close payment KO for cancelled transaction is correct for card wallet for registered user`() =
    runTest {
      val transactionOutcome = ClosePaymentOutcome.KO
      val authRequestedData =
        NpgTransactionGatewayAuthorizationRequestedData(
          LOGO_URI,
          NpgClient.PaymentMethod.PAYPAL.toString(),
          "npgSessionId",
          "npgConfirmPaymentSessionId",
          cardsWalletInfo())
      val authCompletedData = npgTransactionGatewayAuthorizationData(OperationResultDto.DECLINED)

      val activatedEvent = transactionActivateEvent()
      activatedEvent.data.clientId = Transaction.ClientId.IO
      val authEvent =
        TransactionAuthorizationRequestedEvent(
          TRANSACTION_ID,
          TransactionAuthorizationRequestData(
            100,
            10,
            "paymentInstrumentId",
            "pspId",
            PaymentCode.PPAL.name,
            "brokerName",
            "pspChannelCode",
            "paymentMethodName",
            "pspBusinessName",
            false,
            AUTHORIZATION_REQUEST_ID,
            TransactionAuthorizationRequestData.PaymentGateway.NPG,
            "paymentMethodDescription",
            authRequestedData))
      val authCompletedEvent = transactionAuthorizationCompletedEvent(authCompletedData)
      val closureRequestedEvent = transactionClosureRequestedEvent()
      val closureError = transactionClosureErrorEvent()
      val transactionId = activatedEvent.transactionId
      val events =
        listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
          as List<TransactionEvent<Any>>

      val closePaymentResponse =
        ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }

      /* preconditions */
      given(
          transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(
            TRANSACTION_ID))
        .willReturn(events.toFlux())

      given(nodeClient.closePayment(capture(paypalClosePaymentRequestCaptor)))
        .willReturn(Mono.just(closePaymentResponse))

      val fee = authEvent.data.fee
      val amount = authEvent.data.amount
      val totalAmount = amount + fee

      val feeEuroCents = BigDecimal(fee)
      val amountEuroCents = BigDecimal(amount)
      val totalAmountEuroCents = BigDecimal(totalAmount)

      /* test */
      assertEquals(
        closePaymentResponse,
        nodeService.closePayment(TransactionId(transactionId), transactionOutcome))

      val expected =
        PayPalClosePaymentRequestV2Dto().apply {
          outcome = PayPalClosePaymentRequestV2Dto.OutcomeEnum.KO
          this.transactionId = transactionId
          paymentTokens =
            activatedEvent.data.paymentNotices.map { paymentNotice -> paymentNotice.paymentToken }
          transactionDetails =
            TransactionDetailsDto().apply {
              transaction =
                TransactionDto().apply {
                  transactionStatus =
                    TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_DENIED.status
                  this.transactionId = transactionId
                  this.fee = feeEuroCents
                  this.amount = amountEuroCents
                  grandTotal = totalAmountEuroCents
                  this.errorCode =
                    (authCompletedEvent.data.transactionGatewayAuthorizationData
                        as NpgTransactionGatewayAuthorizationData)
                      .errorCode
                  rrn = authCompletedEvent.data.rrn
                  creationDate = ZonedDateTime.parse(activatedEvent.creationDate).toOffsetDateTime()
                  psp =
                    PspDto().apply {
                      idPsp = authEvent.data.pspId
                      brokerName = authEvent.data.brokerName
                      idChannel = authEvent.data.pspChannelCode
                      businessName = authEvent.data.pspBusinessName
                      pspOnUs = authEvent.data.isPspOnUs
                    }
                  authorizationCode = null
                  timestampOperation = authCompletedEvent.data.timestampOperation
                  paymentGateway = authEvent.data.paymentGateway.name
                }
              user = UserDto().apply { type = UserDto.TypeEnum.REGISTERED }
              info =
                InfoDto().apply {
                  type = authEvent.data.paymentTypeCode
                  clientId = Transaction.ClientId.IO.name
                  brand = authEvent.data.paymentTypeCode
                  brandLogo =
                    (authEvent.data.transactionGatewayAuthorizationRequestedData
                        as NpgTransactionGatewayAuthorizationRequestedData)
                      .logo
                      .toString()
                  paymentMethodName = authEvent.data.paymentMethodName
                  bin = NPG_WALLET_CARD_BIN
                  lastFourDigits = NPG_WALLET_CARD_LAST_FOUR_DIGITS
                }
            }
          additionalPaymentInformations = null
        }

      assertEquals(expected, paypalClosePaymentRequestCaptor.value)
    }

  @Test
  fun `ClosePaymentRequestV2Dto for close payment KO for authorization KO is correct for card wallet for registered user`() =
    runTest {
      val transactionOutcome = ClosePaymentOutcome.KO
      val authRequestedData =
        NpgTransactionGatewayAuthorizationRequestedData(
          LOGO_URI,
          NpgClient.PaymentMethod.PAYPAL.toString(),
          "npgSessionId",
          "npgConfirmPaymentSessionId",
          cardsWalletInfo())
      val authCompletedData = npgTransactionGatewayAuthorizationData(OperationResultDto.DECLINED)

      val activatedEvent = transactionActivateEvent()
      activatedEvent.data.clientId = Transaction.ClientId.IO
      val authEvent =
        TransactionAuthorizationRequestedEvent(
          TRANSACTION_ID,
          TransactionAuthorizationRequestData(
            100,
            10,
            "paymentInstrumentId",
            "pspId",
            PaymentCode.PPAL.name,
            "brokerName",
            "pspChannelCode",
            "paymentMethodName",
            "pspBusinessName",
            false,
            AUTHORIZATION_REQUEST_ID,
            TransactionAuthorizationRequestData.PaymentGateway.NPG,
            "paymentMethodDescription",
            authRequestedData))
      val authCompletedEvent = transactionAuthorizationCompletedEvent(authCompletedData)
      val closureRequestedEvent = transactionClosureRequestedEvent()
      val closureError = transactionClosureErrorEvent()
      val transactionId = activatedEvent.transactionId
      val events =
        listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
          as List<TransactionEvent<Any>>

      val closePaymentResponse =
        ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }

      /* preconditions */
      given(
          transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(
            TRANSACTION_ID))
        .willReturn(events.toFlux())

      given(nodeClient.closePayment(capture(paypalClosePaymentRequestCaptor)))
        .willReturn(Mono.just(closePaymentResponse))

      val fee = authEvent.data.fee
      val amount = authEvent.data.amount
      val totalAmount = amount + fee

      val feeEuroCents = BigDecimal(fee)
      val amountEuroCents = BigDecimal(amount)
      val totalAmountEuroCents = BigDecimal(totalAmount)

      /* test */
      assertEquals(
        closePaymentResponse,
        nodeService.closePayment(TransactionId(transactionId), transactionOutcome))

      val expected =
        PayPalClosePaymentRequestV2Dto().apply {
          outcome = PayPalClosePaymentRequestV2Dto.OutcomeEnum.KO
          this.transactionId = transactionId
          paymentTokens =
            activatedEvent.data.paymentNotices.map { paymentNotice -> paymentNotice.paymentToken }
          transactionDetails =
            TransactionDetailsDto().apply {
              transaction =
                TransactionDto().apply {
                  transactionStatus =
                    TransactionDetailsStatusEnum.TRANSACTION_DETAILS_STATUS_DENIED.status
                  this.transactionId = transactionId
                  this.fee = feeEuroCents
                  this.amount = amountEuroCents
                  grandTotal = totalAmountEuroCents
                  this.errorCode =
                    (authCompletedEvent.data.transactionGatewayAuthorizationData
                        as NpgTransactionGatewayAuthorizationData)
                      .errorCode
                  rrn = authCompletedEvent.data.rrn
                  creationDate = ZonedDateTime.parse(activatedEvent.creationDate).toOffsetDateTime()
                  psp =
                    PspDto().apply {
                      idPsp = authEvent.data.pspId
                      brokerName = authEvent.data.brokerName
                      idChannel = authEvent.data.pspChannelCode
                      businessName = authEvent.data.pspBusinessName
                      pspOnUs = authEvent.data.isPspOnUs
                    }
                  authorizationCode = null
                  timestampOperation = authCompletedEvent.data.timestampOperation
                  paymentGateway = authEvent.data.paymentGateway.name
                }
              user = UserDto().apply { type = UserDto.TypeEnum.REGISTERED }
              info =
                InfoDto().apply {
                  type = authEvent.data.paymentTypeCode
                  clientId = Transaction.ClientId.IO.name
                  brand = authEvent.data.paymentTypeCode
                  brandLogo =
                    (authEvent.data.transactionGatewayAuthorizationRequestedData
                        as NpgTransactionGatewayAuthorizationRequestedData)
                      .logo
                      .toString()
                  paymentMethodName = authEvent.data.paymentMethodName
                  bin = NPG_WALLET_CARD_BIN
                  lastFourDigits = NPG_WALLET_CARD_LAST_FOUR_DIGITS
                }
            }
          additionalPaymentInformations = null
        }

      assertEquals(expected, paypalClosePaymentRequestCaptor.value)
    }

  @Test
  fun `Should return error building ClosePaymentRequestV2Dto OK for registered user when user id is null`() =
    runTest {
      val transactionOutcome = ClosePaymentOutcome.OK
      val authRequestedData =
        NpgTransactionGatewayAuthorizationRequestedData(
          LOGO_URI,
          NpgClient.PaymentMethod.PAYPAL.toString(),
          "npgSessionId",
          "npgConfirmPaymentSessionId",
          cardsWalletInfo())
      val authData = npgTransactionGatewayAuthorizationData(OperationResultDto.EXECUTED)

      val activatedEvent = transactionActivateEvent()
      activatedEvent.data.clientId = Transaction.ClientId.IO
      activatedEvent.data.userId = null
      val authEvent =
        TransactionAuthorizationRequestedEvent(
          TRANSACTION_ID,
          TransactionAuthorizationRequestData(
            100,
            10,
            "paymentInstrumentId",
            "pspId",
            PaymentCode.PPAL.name,
            "brokerName",
            "pspChannelCode",
            "paymentMethodName",
            "pspBusinessName",
            false,
            AUTHORIZATION_REQUEST_ID,
            TransactionAuthorizationRequestData.PaymentGateway.NPG,
            "paymentMethodDescription",
            authRequestedData))
      val authCompletedEvent = transactionAuthorizationCompletedEvent(authData)
      val closureRequestedEvent = transactionClosureRequestedEvent()
      val closureError = transactionClosureErrorEvent()
      val transactionId = activatedEvent.transactionId
      val events =
        listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
          as List<TransactionEvent<Any>>

      val closePaymentResponse =
        ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }
      val userFiscalCode = "userFiscalCode"

      /* preconditions */
      given(confidentialDataUtils.decryptWalletSessionToken(any()))
        .willReturn(mono { userFiscalCode })
      given(
          transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(
            TRANSACTION_ID))
        .willReturn(events.toFlux())

      given(nodeClient.closePayment(capture(paypalClosePaymentRequestCaptor)))
        .willReturn(Mono.just(closePaymentResponse))

      /* test */
      val result = runCatching {
        nodeService.closePayment(TransactionId(transactionId), transactionOutcome)
      }
      assertTrue(result.isFailure)
      assertEquals(
        "Invalid user id null for transaction with clientId: [IO]",
        result.exceptionOrNull()?.message)
    }

  @Test
  fun `Should return error building ClosePaymentRequestV2Dto OK for registered user for unhandled client id`() =
    runTest {
      val transactionOutcome = ClosePaymentOutcome.OK
      val authRequestedData =
        NpgTransactionGatewayAuthorizationRequestedData(
          LOGO_URI,
          NpgClient.PaymentMethod.PAYPAL.toString(),
          "npgSessionId",
          "npgConfirmPaymentSessionId",
          cardsWalletInfo())
      val authData = npgTransactionGatewayAuthorizationData(OperationResultDto.EXECUTED)

      val activatedEvent = transactionActivateEvent()
      activatedEvent.data.clientId = null
      activatedEvent.data.userId = null
      val authEvent =
        TransactionAuthorizationRequestedEvent(
          TRANSACTION_ID,
          TransactionAuthorizationRequestData(
            100,
            10,
            "paymentInstrumentId",
            "pspId",
            PaymentCode.PPAL.name,
            "brokerName",
            "pspChannelCode",
            "paymentMethodName",
            "pspBusinessName",
            false,
            AUTHORIZATION_REQUEST_ID,
            TransactionAuthorizationRequestData.PaymentGateway.NPG,
            "paymentMethodDescription",
            authRequestedData))
      val authCompletedEvent = transactionAuthorizationCompletedEvent(authData)
      val closureRequestedEvent = transactionClosureRequestedEvent()
      val closureError = transactionClosureErrorEvent()
      val transactionId = activatedEvent.transactionId
      val events =
        listOf(activatedEvent, authEvent, authCompletedEvent, closureRequestedEvent, closureError)
          as List<TransactionEvent<Any>>

      val closePaymentResponse =
        ClosePaymentResponseDto().apply { outcome = ClosePaymentResponseDto.OutcomeEnum.OK }
      val userFiscalCode = "userFiscalCode"

      /* preconditions */
      given(confidentialDataUtils.decryptWalletSessionToken(any()))
        .willReturn(mono { userFiscalCode })
      given(
          transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(
            TRANSACTION_ID))
        .willReturn(events.toFlux())

      given(nodeClient.closePayment(capture(paypalClosePaymentRequestCaptor)))
        .willReturn(Mono.just(closePaymentResponse))

      /* test */
      val result = runCatching {
        nodeService.closePayment(TransactionId(transactionId), transactionOutcome)
      }
      assertTrue(result.isFailure)
      assertEquals("Unhandled client id: [null]", result.exceptionOrNull()?.message)
    }
}
