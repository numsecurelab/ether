package io.horizontalsystems.erc20kit.core

import io.horizontalsystems.erc20kit.models.Transaction
import io.horizontalsystems.ethereumkit.core.hexStringToByteArray
import io.horizontalsystems.ethereumkit.models.EthereumLog
import io.horizontalsystems.ethereumkit.models.TransactionStatus
import io.horizontalsystems.ethereumkit.spv.core.toBigInteger
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import java.math.BigInteger
import java.util.concurrent.Executors
import java.util.logging.Logger

class TransactionManager(
        private val contractAddress: ByteArray,
        private val address: ByteArray,
        private val storage: ITransactionStorage,
        private val dataProvider: IDataProvider,
        private val transactionBuilder: ITransactionBuilder)
    : ITransactionManager {

    private val scheduler = Schedulers.from(Executors.newSingleThreadExecutor())

    private val logger = Logger.getLogger("TransactionManager")
    private val disposables = CompositeDisposable()
    override var listener: ITransactionManagerListener? = null
    override val lastTransactionBlockHeight: Long?
        get() = storage.lastTransactionBlockHeight

    override fun getTransactions(fromTransaction: TransactionKey?, limit: Int?): Single<List<Transaction>> {
        return storage.getTransactions(fromTransaction, limit)
    }

    private fun handleLogs(logs: List<EthereumLog>) {
        val nonZeroLogs = logs.filter { log ->
            logs.count { it.transactionHash.contentEquals(log.transactionHash) } == 1 ||
                    log.data.hexStringToByteArray().toBigInteger() != BigInteger.ZERO
        }

        val pendingTransactions = storage.getPendingTransactions()

        val updatedTransactions = nonZeroLogs.map { log ->
            var interTransactionIndex = log.logIndex
            val value = log.data.hexStringToByteArray().toBigInteger()
            val from = log.topics[1].hexStringToByteArray().copyOfRange(12, 32)
            val to = log.topics[2].hexStringToByteArray().copyOfRange(12, 32)

            if (pendingTransactions.count {
                        it.transactionHash.contentEquals(log.transactionHash.hexStringToByteArray())
                                && it.from.contentEquals(from)
                                && it.to.contentEquals(to)
                    } > 0) {
                interTransactionIndex = 0
            }

            val transaction = Transaction(
                    transactionHash = log.transactionHash.hexStringToByteArray(),
                    interTransactionIndex = interTransactionIndex,
                    transactionIndex = log.transactionIndex,
                    from = from,
                    to = to,
                    value = value,
                    timestamp = log.timestamp ?: System.currentTimeMillis() / 1000)

            transaction.logIndex = log.logIndex
            transaction.blockHash = log.blockHash.hexStringToByteArray()
            transaction.blockNumber = log.blockNumber

            transaction
        }

        if(pendingTransactions.isEmpty()){
            finishSync(updatedTransactions)
            return
        }

        dataProvider.getTransactionStatuses(pendingTransactions.map { it.transactionHash })
                .observeOn(scheduler)
                .map { statuses ->
                    updateFailStatus(pendingTransactions, statuses)
                }.subscribe(
                        { failedTransactions ->
                            failedTransactions?.let {
                                finishSync(updatedTransactions.plus(failedTransactions))
                            }
                        },
                        { error -> finishSync(updatedTransactions) })
                .let {
                    disposables.add(it)
                }
    }

    private fun finishSync(transactions: List<Transaction>) {
        storage.save(transactions)
        listener?.onSyncSuccess(transactions)
    }

    private fun updateFailStatus(pendingTransactions: List<Transaction>,
                                 statuses: Map<ByteArray, TransactionStatus>): List<Transaction>? {
        return statuses.mapNotNull { (hash, status) ->
            if (status == TransactionStatus.FAILED || status == TransactionStatus.NOTFOUND) {
                pendingTransactions.find { it.transactionHash.contentEquals(hash) }?.let { foundTx ->
                    foundTx.isError = true
                    foundTx
                }
            } else {
                null
            }
        }
    }

    override fun sync() {
        val lastBlockHeight = dataProvider.lastBlockHeight
        val lastTransactionBlockHeight = storage.lastTransactionBlockHeight ?: 0

        dataProvider.getTransactionLogs(contractAddress, address, lastTransactionBlockHeight + 1, lastBlockHeight)
                .subscribeOn(scheduler)
                .subscribe({ logs ->
                               handleLogs(logs)
                           }, {
                               logger.warning("Transaction sync error: ${it.message}")
                               listener?.onSyncTransactionsError()
                           })
                .let {
                    disposables.add(it)
                }
    }

    override fun send(to: ByteArray, value: BigInteger, gasPrice: Long, gasLimit: Long): Single<Transaction> {
        val transactionInput = transactionBuilder.transferTransactionInput(to, value)

        return dataProvider.send(contractAddress, transactionInput, gasPrice, gasLimit)
                .map { hash ->
                    Transaction(transactionHash = hash,
                                from = address,
                                to = to,
                                value = value)
                }.doOnSuccess { transaction ->
                    storage.save(listOf(transaction))
                }
    }

    override fun getTransactionInput(to: ByteArray, value: BigInteger): ByteArray {
        return transactionBuilder.transferTransactionInput(to, value)
    }
}
