package it.pagopa.ecommerce.eventdispatcher.exceptions

import it.pagopa.ecommerce.commons.domain.TransactionId

class NpgPaymentGatewayStateException(transactionID: TransactionId, state: String? = "N/A") :
  RuntimeException("Transaction with id ${transactionID.value()} npg state is $state")
