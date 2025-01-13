package it.pagopa.ecommerce.payment.requests.warmup.utils

import com.azure.spring.messaging.checkpoint.Checkpointer
import reactor.core.publisher.Mono

object DummyCheckpointer : Checkpointer {
  override fun success(): Mono<Void> = Mono.empty()
  override fun failure(): Mono<Void> = Mono.empty()
}

object WarmupRequests {

  fun getTransactionAuthorizationOutcomeWaitingEvent(): ByteArray {
    val jsonString =
      """
          {
              "event": {
                  "_class": "it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationOutcomeWaitingEvent",
                  "id": "7ee814b9-8bb8-4f61-9204-2aa55cb56773",
                  "transactionId": "00000000000000000000000000000000",
                  "creationDate": "2025-01-10T14:28:47.843515440Z[Etc/UTC]",
                  "data": {
                      "retryCount": 1
                  },
                  "eventCode": "TRANSACTION_AUTHORIZATION_OUTCOME_WAITING_EVENT"
              },
              "tracingInfo": {
                  "traceparent": "00-5868efa082297543570dafff7d53c70b-56f1d9262e6ee6cf-00",
                  "tracestate": null,
                  "baggage": null
              }
          }
          """

    return jsonString.toByteArray()
  }

  fun getTransactionUserReceiptAddErrorEvent(): ByteArray {
    val jsonString =
      """
        {
            "event": {
                "_class": "it.pagopa.ecommerce.commons.documents.v2.TransactionUserReceiptAddErrorEvent",
                "id": "12345678-1234-1234-1234-123456789012",
                "transactionId": "00000000000000000000000000000000",
                "creationDate": "2025-01-13T10:26:33.000Z[Etc/UTC]",
                "data": {
                    "responseOutcome": "KO",
                    "language": "en",
                    "paymentDate": "2025-01-12T10:00:00.000Z"
                },
                "eventCode": "TRANSACTION_ADD_USER_RECEIPT_ERROR_EVENT"
            },
            "tracingInfo": {
                "traceparent": "00-abcdef1234567890abcdef1234567890-abcdef1234567890-00",
                "tracestate": null,
                "baggage": null
            }
        }

        """
    return jsonString.toByteArray()
  }

  fun getTransactionAuthorizationRequestedEvent(): ByteArray {
    val jsonString =
      """
        {
            "event": {
                "_class": "it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationRequestedEvent",
                "id": "7ee814b9-8bb8-4f61-9204-2aa55cb56773",
                "transactionId": "00000000000000000000000000000000",
                "creationDate": "2025-01-10T14:28:47.843515440Z[Etc/UTC]",
                "data": {
                    "amount": 50000,
                    "fee": 0,
                    "paymentInstrumentId": "992ffbae-3ec3-4604-b8b4-c7c406d087b6",
                    "pspId": "CIPBITMM",
                    "paymentTypeCode": "CP",
                    "brokerName": "idBrokerPsp1",
                    "pspChannelCode": "idChannel1",
                    "paymentMethodName": "CARDS",
                    "pspBusinessName": "bundleName1",
                    "authorizationRequestId": "E1736519327527WJzV",
                    "paymentGateway": "NPG",
                    "paymentMethodDescription": "description2",
                    "transactionGatewayAuthorizationRequestedData": {
                        "type": "NPG",
                        "logo": "asset",
                        "brand": "VISA",
                        "sessionId": "sessionId",
                        "confirmPaymentSessionId": null,
                        "walletInfo": null
                    },
                    "pspOnUs": true
                },
                "eventCode": "TRANSACTION_AUTHORIZATION_REQUESTED_EVENT"
            },
            "tracingInfo": {
                "traceparent": "00-5868efa082297543570dafff7d53c70b-56f1d9262e6ee6cf-00",
                "tracestate": null,
                "baggage": null
            }
        }
        """
    return jsonString.toByteArray()
  }

  fun getTransactionClosureRequestedEvent(): ByteArray {
    val jsonString =
      """
        {
            "event": {
                "_class": "it.pagopa.ecommerce.commons.documents.v2.TransactionClosureRequestedEvent",
                "id": "7ee814b9-8bb8-4f61-9204-2aa55cb56773",
                "transactionId": "00000000000000000000000000000000",
                "creationDate": "2025-01-10T14:28:47.843515440Z[Etc/UTC]",
                "data": null,
                "eventCode": "TRANSACTION_CLOSURE_REQUESTED_EVENT"
            },
            "tracingInfo": {
                "traceparent": "00-5868efa082297543570dafff7d53c70b-56f1d9262e6ee6cf-00",
                "tracestate": null,
                "baggage": null
            }
        }
      """
    return jsonString.toByteArray()
  }

  fun getTransactionClosureErrorEvent(): ByteArray {
    val jsonString =
      """
        {
          "event": {
              "_class": "it.pagopa.ecommerce.commons.documents.v2.TransactionClosureErrorEvent",
              "id": "7ee814b9-8bb8-4f61-9204-2aa55cb56773",
              "transactionId": "00000000000000000000000000000000",
              "creationDate": "2025-01-10T14:28:47.843515440Z[Etc/UTC]",
              "data": {
                  "httpErrorCode": "INTERNAL_SERVER_ERROR",
                  "errorDescription": "Sample error message",
                  "errorType": "KO_RESPONSE_RECEIVED"
              },
              "eventCode": "TRANSACTION_CLOSURE_ERROR_EVENT"
          },
          "tracingInfo": {
              "traceparent": "00-5868efa082297543570dafff7d53c70b-56f1d9262e6ee6cf-00",
              "tracestate": null,
              "baggage": null
          }
        }
      """
    return jsonString.toByteArray()
  }

  fun getTransactionExpiredEvent(): ByteArray {
    val jsonString =
      """
        {
            "event": {
                "_class": "it.pagopa.ecommerce.commons.documents.v2.TransactionExpiredEvent",
                "id": "7ee814b9-8bb8-4f61-9204-2aa55cb56773",
                "transactionId": "00000000000000000000000000000000",
                "creationDate": "2025-01-10T14:28:47.843515440Z[Etc/UTC]",
                "data": {
                    "statusBeforeExpiration": "AUTHORIZATION_COMPLETED"
                },
                "eventCode": "TRANSACTION_EXPIRED_EVENT"
            },
            "tracingInfo": {
                "traceparent": "00-5868efa082297543570dafff7d53c70b-56f1d9262e6ee6cf-00",
                "tracestate": null,
                "baggage": null
            }
        }
      """
    return jsonString.toByteArray()
  }

  fun getTransactionRefundRequestedEvent(): ByteArray {
    val jsonString =
      """
        {
            "event": {
                "_class": "it.pagopa.ecommerce.commons.documents.v2.TransactionRefundRequestedEvent",
                "id": "abcdef12-3456-7890-abcd-ef1234567890",
                "transactionId": "00000000000000000000000000000000",
                "creationDate": "2025-01-13T10:30:00.000Z[Etc/UTC]",
                "data": {
                    "gatewayAuthData": null,
                    "statusBeforeRefunded": "AUTHORIZATION_REQUESTED"
                },
                "eventCode": "TRANSACTION_REFUND_REQUESTED_EVENT"
            },
            "tracingInfo": {
                "traceparent": "00-1234567890abcdef1234567890abcdef-1234567890abcdef-00",
                "tracestate": null,
                "baggage": null
            }
        }
      """
    return jsonString.toByteArray()
  }

  fun getTransactionUserReceiptRequestedEvent(): ByteArray {
    val jsonString =
      """
        {
            "event": {
                "_class": "it.pagopa.ecommerce.commons.documents.v2.TransactionUserReceiptRequestedEvent",
                "id": "7ee814b9-8bb8-4f61-9204-2aa55cb56773",
                "transactionId": "00000000000000000000000000000000",
                "creationDate": "2025-01-10T14:28:47.843515440Z[Etc/UTC]",
                "data": {
                    "responseOutcome": "OK",
                    "language": "en",
                    "paymentDate": "2025-01-10T14:28:47.843515440Z[Etc/UTC]"
                },
                "eventCode": "TRANSACTION_USER_RECEIPT_REQUESTED_EVENT"
            },
            "tracingInfo": {
                "traceparent": "00-5868efa082297543570dafff7d53c70b-56f1d9262e6ee6cf-00",
                "tracestate": null,
                "baggage": null
            }
        }
      """
    return jsonString.toByteArray()
  }

  fun getTransactionRefundRetriedEvent(): ByteArray {
    val jsonString =
      """
        {
            "event": {
                "_class": "it.pagopa.ecommerce.commons.documents.v2.TransactionRefundRetriedEvent",
                "id": "7ee814b9-8bb8-4f61-9204-2aa55cb56773",
                "transactionId": "00000000000000000000000000000000",
                "creationDate": "2025-01-10T14:28:47.843515440Z[Etc/UTC]",
                "data": {
                    "transactionGatewayAuthorizationData": {
                        "type": "PGS"
                    },
                    "retryCount": 1
                },
                "eventCode": "TRANSACTION_REFUND_RETRIED_EVENT"
            },
            "tracingInfo": {
                "traceparent": "00-5868efa082297543570dafff7d53c70b-56f1d9262e6ee6cf-00",
                "tracestate": null,
                "baggage": null
            }
        }
      """
    return jsonString.toByteArray()
  }
}