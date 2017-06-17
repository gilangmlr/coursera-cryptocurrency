import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Iterator;


public class TxHandler {
    private UTXOPool utxoPool;

    /**
     * Creates a public ledger whose current UTXOPool (collection of unspent transaction outputs) is
     * {@code utxoPool}. This should make a copy of utxoPool by using the UTXOPool(UTXOPool uPool)
     * constructor.
     */
    public TxHandler(UTXOPool utxoPool) {
        this.utxoPool = new UTXOPool(utxoPool);
    }

    /**
     * @return true if:
     * (1) all outputs claimed by {@code tx} are in the current UTXO pool, 
     * (2) the signatures on each input of {@code tx} are valid, 
     * (3) no UTXO is claimed multiple times by {@code tx},
     * (4) all of {@code tx}s output values are non-negative, and
     * (5) the sum of {@code tx}s input values is greater than or equal to the sum of its output
     *     values; and false otherwise.
     */
    public boolean isValidTx(Transaction tx) {
        double totalInput = 0.0;
        java.util.HashSet<UTXO> utxos = new java.util.HashSet<UTXO>();
        int i = 0;
        for (Transaction.Input in: tx.getInputs()) {
            UTXO utxo = new UTXO(in.prevTxHash, in.outputIndex);
            if (!utxoPool.contains(utxo)) {
                return false;
            }

            Transaction.Output out = utxoPool.getTxOutput(utxo);
            totalInput += out.value;

            boolean verified = Crypto.verifySignature(out.address, tx.getRawDataToSign(i++), in.signature);
            if (!verified) {
                return false;
            }

            if (!utxos.add(utxo)) {
                return false;
            }
        }

        double totalOutput = 0.0;
        for (Transaction.Output out: tx.getOutputs()) {
            totalOutput += out.value;

            if (out.value < 0) {
                return false;
            }
        }

        if (totalInput < totalOutput) {
            return false;
        }

        return true;
    }

    /**
     * Handles each epoch by receiving an unordered array of proposed transactions, checking each
     * transaction for correctness, returning a mutually valid array of accepted transactions, and
     * updating the current UTXO pool as appropriate.
     */
    public Transaction[] handleTxs(Transaction[] possibleTxs) {
        HashSet<Transaction> validTxs = new HashSet<Transaction>();
        for (Transaction tx: possibleTxs) {
            if (!isValidTx(tx)) {
                continue;
            }

            validTxs.add(tx);
            for (Transaction.Input in: tx.getInputs()) {
                UTXO utxo = new UTXO(in.prevTxHash, in.outputIndex);
                utxoPool.removeUTXO(utxo);
            }

            int i = 0;
            for (Transaction.Output out: tx.getOutputs()) {
                UTXO utxo = new UTXO(tx.getHash(), i++);
                utxoPool.addUTXO(utxo, out);
            }
        }

        Transaction[] arrayOfValidTxs = new Transaction[validTxs.size()];
        int i = 0;
        for (Transaction tx: validTxs) {
            arrayOfValidTxs[i++] = tx;
        }
        return arrayOfValidTxs;
    }

}
