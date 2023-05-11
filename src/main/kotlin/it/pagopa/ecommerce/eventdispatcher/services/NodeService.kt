package it.pagopa.ecommerce.eventdispatcher.services

import it.pagopa.ecommerce.commons.domain.v1.*
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto
import it.pagopa.ecommerce.eventdispatcher.client.NodeClient
import it.pagopa.ecommerce.eventdispatcher.exceptions.BadTransactionStatusException
import it.pagopa.ecommerce.eventdispatcher.queues.reduceEvents
import it.pagopa.ecommerce.eventdispatcher.repositories.TransactionsEventStoreRepository
import it.pagopa.generated.ecommerce.nodo.v2.dto.AdditionalPaymentInformationsDto
import it.pagopa.generated.ecommerce.nodo.v2.dto.ClosePaymentRequestV2Dto
import it.pagopa.generated.ecommerce.nodo.v2.dto.ClosePaymentResponseDto
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.mono
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono

const val TIPO_VERSAMENTO_CP = "CP"

@Service
class NodeService(
  @Autowired private val nodeClient: NodeClient,
  @Autowired private val transactionsEventStoreRepository: TransactionsEventStoreRepository<Any>
) {
  var logger: Logger = LoggerFactory.getLogger(NodeService::class.java)

  suspend fun closePayment(
    transactionId: TransactionId,
    transactionOutcome: ClosePaymentRequestV2Dto.OutcomeEnum
  ): ClosePaymentResponseDto {

    val baseTransaction =
      reduceEvents(
        Mono.just(transactionId.value()), transactionsEventStoreRepository, EmptyTransaction())

    val closePaymentRequest =
      baseTransaction.flatMap {
        when (it.status) {
          TransactionStatusDto.CLOSURE_ERROR -> {
            when (transactionOutcome) {
              ClosePaymentRequestV2Dto.OutcomeEnum.KO ->
                mono {
                  ClosePaymentRequestV2Dto().apply {
                    paymentTokens = it.paymentNotices.map { el -> el.paymentToken.value }
                    outcome = transactionOutcome
                    this.transactionId = transactionId.toString()
                  }
                }
              ClosePaymentRequestV2Dto.OutcomeEnum.OK ->
                mono { it }
                  .cast(TransactionWithClosureError::class.java)
                  .flatMap { baseT ->
                    val transactionAtPreviousState = baseT.transactionAtPreviousState()
                    mono {
                      transactionAtPreviousState
                        .map { event ->
                          event.fold(
                            {
                              ClosePaymentRequestV2Dto().apply {
                                paymentTokens =
                                  baseT.paymentNotices.map { el -> el.paymentToken.value }
                                outcome = transactionOutcome
                                this.transactionId = transactionId.toString()
                              }
                            },
                            { authCompleted ->
                              ClosePaymentRequestV2Dto().apply {
                                paymentTokens =
                                  authCompleted.paymentNotices.map { paymentNotice ->
                                    paymentNotice.paymentToken.value
                                  }
                                outcome = transactionOutcome
                                idPSP = authCompleted.transactionAuthorizationRequestData.pspId
                                paymentMethod =
                                  authCompleted.transactionAuthorizationRequestData.paymentTypeCode
                                idBrokerPSP =
                                  authCompleted.transactionAuthorizationRequestData.brokerName
                                idChannel =
                                  authCompleted.transactionAuthorizationRequestData.pspChannelCode
                                this.transactionId = transactionId.toString()
                                totalAmount =
                                  (authCompleted.transactionAuthorizationRequestData.amount.plus(
                                      authCompleted.transactionAuthorizationRequestData.fee))
                                    .toBigDecimal()
                                fee =
                                  authCompleted.transactionAuthorizationRequestData.fee
                                    .toBigDecimal()
                                this.timestampOperation =
                                  OffsetDateTime.parse(
                                    authCompleted.transactionAuthorizationCompletedData
                                      .timestampOperation,
                                    DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                                additionalPaymentInformations =
                                  AdditionalPaymentInformationsDto().apply {
                                    tipoVersamento = TIPO_VERSAMENTO_CP
                                    outcomePaymentGateway =
                                      AdditionalPaymentInformationsDto.OutcomePaymentGatewayEnum
                                        .valueOf(
                                          authCompleted.transactionAuthorizationCompletedData
                                            .authorizationResultDto
                                            .toString())
                                    this.authorizationCode =
                                      authCompleted.transactionAuthorizationCompletedData
                                        .authorizationCode
                                    fee =
                                      authCompleted.transactionAuthorizationRequestData.fee
                                        .toBigDecimal()
                                    this.timestampOperation =
                                      OffsetDateTime.parse(
                                        authCompleted.transactionAuthorizationCompletedData
                                          .timestampOperation,
                                        DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                                    rrn = authCompleted.transactionAuthorizationCompletedData.rrn
                                  }
                              }
                            })
                        }
                        .orElseThrow {
                          RuntimeException(
                            "Unexpected transactionAtPreviousStep: ${baseT.transactionAtPreviousState}")
                        }
                    }
                  }
            }
          }
          TransactionStatusDto.CANCELLATION_REQUESTED ->
            mono {
              ClosePaymentRequestV2Dto().apply {
                paymentTokens = it.paymentNotices.map { el -> el.paymentToken.value }
                outcome = transactionOutcome
                this.transactionId = transactionId.toString()
              }
            }
          else -> {
            Mono.error {
              BadTransactionStatusException(
                transactionId = it.transactionId,
                expected = TransactionStatusDto.CLOSURE_ERROR, // fix multiple status
                actual = it.status)
            }
          }
        }
      }

    return nodeClient.closePayment(closePaymentRequest.awaitSingle()).awaitSingle()
  }
}
