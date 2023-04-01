package it.pagopa.ecommerce.eventdispatcher.utils

import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationRequestData
import it.pagopa.generated.ecommerce.gateway.v1.dto.PostePayRefundResponseDto
import it.pagopa.generated.ecommerce.nodo.v2.dto.ClosePaymentRequestV2Dto
import java.time.OffsetDateTime
import java.util.*

fun getMockedClosePaymentRequest(
  transactionId: UUID,
  outcome: ClosePaymentRequestV2Dto.OutcomeEnum
): ClosePaymentRequestV2Dto {

  val authEventData =
    TransactionAuthorizationRequestData(
      100,
      1,
      "paymentInstrumentId",
      "pspId",
      "paymentTypeCode",
      "brokerName",
      "pspChannelCode",
      "requestId",
      "pspBusinessName",
      "authorizationRequestId",
    )

  return ClosePaymentRequestV2Dto().apply {
    paymentTokens = listOf(UUID.randomUUID().toString())
    this.outcome = outcome
    idPSP = authEventData.pspId
    paymentMethod = authEventData.paymentTypeCode
    idBrokerPSP = authEventData.brokerName
    idChannel = authEventData.pspChannelCode
    this.transactionId = transactionId.toString()
    totalAmount = (authEventData.amount + authEventData.fee).toBigDecimal()
    timestampOperation = OffsetDateTime.now()
  }
}

fun getMockedRefundRequest(
  paymentId: String?,
  result: String = "success"
): PostePayRefundResponseDto {
  if (result == "success") {
    return PostePayRefundResponseDto()
      .requestId(UUID.randomUUID().toString())
      .refundOutcome(result)
      .error("")
      .paymentId(paymentId ?: UUID.randomUUID().toString())
  } else {
    return PostePayRefundResponseDto()
      .requestId(UUID.randomUUID().toString())
      .refundOutcome(result)
      .error(result)
      .paymentId(paymentId ?: UUID.randomUUID().toString())
  }
}