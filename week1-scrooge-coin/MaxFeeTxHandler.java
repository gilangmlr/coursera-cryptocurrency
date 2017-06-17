import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Iterator;


public class MaxFeeTxHandler {
    private UTXOPool utxoPool;

    /**
     * Creates a public ledger whose current UTXOPool (collection of unspent transaction outputs) is
     * {@code utxoPool}. This should make a copy of utxoPool by using the UTXOPool(UTXOPool uPool)
     * constructor.
     */
    public MaxFeeTxHandler(UTXOPool utxoPool) {
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
            // (1)
            if (!utxoPool.contains(utxo)) {
                return false;
            }

            Transaction.Output out = utxoPool.getTxOutput(utxo);
            totalInput += out.value;

            boolean verified = Crypto.verifySignature(out.address, tx.getRawDataToSign(i++), in.signature);
            // (2)
            if (!verified) {
                return false;
            }

            // (3)
            if (!utxos.add(utxo)) {
                return false;
            }
        }

        double totalOutput = 0.0;
        for (Transaction.Output out: tx.getOutputs()) {
            totalOutput += out.value;

            // (4)
            if (out.value < 0) {
                return false;
            }
        }

        // (5)
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
        ArrayList<ArrayList<Transaction>> doubleSpendCluster = getDoubleSpendCluster(possibleTxs);
        HashSet<Transaction> doubleSpendTxs = getDoubleSpendTxs(doubleSpendCluster);
        HashSet<Transaction> nonDoubleSpendTxs = getNonDoubleSpendTxs(possibleTxs, doubleSpendTxs);

        ArrayList<ArrayList<Transaction>> doubleSpendCombinations = generateDoubleSpendCombinations(doubleSpendCluster);
        double[] fees = new double[doubleSpendCombinations.size()];
        UTXOPool originalUtxoPool = new UTXOPool(utxoPool);
        
        Transaction[] combinationTxs = new Transaction[nonDoubleSpendTxs.size() + doubleSpendCluster.size()];
        int i = 0;
        for (Transaction tx: nonDoubleSpendTxs) {
            combinationTxs[i++] = tx;
        }

        for (i = 0; i < doubleSpendCombinations.size(); i++) {
            int j = 0;
            for (int k = 0; k < doubleSpendCombinations.get(i).size(); k++) {
                ArrayList<Transaction> arr = doubleSpendCombinations.get(i);
                combinationTxs[nonDoubleSpendTxs.size() + j] = arr.get(j);
                j++;
            }
            
            Transaction[] sortedCombinationTxs = topologicalSort(combinationTxs);

            fees[i] = simulateFeeFromCombination(sortedCombinationTxs);

            utxoPool = new UTXOPool(originalUtxoPool);
        }

        double maxFee = 0.0;
        int maxFeeIndex = 0;
        for (i = 0; i < fees.length; i++) {
            if (fees[i] > maxFee) {
                maxFee = fees[i];
                maxFeeIndex = i;
            }
        }

        Transaction[] maxFeeTxs = new Transaction[nonDoubleSpendTxs.size() + doubleSpendCluster.size()];
        i = 0;
        for (Transaction tx: nonDoubleSpendTxs) {
            maxFeeTxs[i++] = tx;
        }

        if (doubleSpendTxs.size() > 0) {
            for (Transaction tx: doubleSpendCombinations.get(maxFeeIndex)) {
                maxFeeTxs[i] = tx;
                i++;
            }
        }

        maxFeeTxs = topologicalSort(maxFeeTxs);

        return handleTxsNonMaxFee(maxFeeTxs);
    }

    private double simulateFeeFromCombination(Transaction[] sortedCombinationTxs) {
        HashSet<Transaction> validTxs = new HashSet<Transaction>();
        double txFee = 0.0;
        for (Transaction tx: sortedCombinationTxs) {
            if (!isValidTx(tx)) {
                continue;
            }

            validTxs.add(tx);

            int i = 0;
            for (Transaction.Output out: tx.getOutputs()) {
                UTXO utxo = new UTXO(tx.getHash(), i++);
                utxoPool.addUTXO(utxo, out);
            }

            txFee += getTransactionFee(tx);

            for (Transaction.Input in: tx.getInputs()) {
                UTXO utxo = new UTXO(in.prevTxHash, in.outputIndex);
                utxoPool.removeUTXO(utxo);
            }
        }
        
        return txFee;
    }

    private ArrayList<ArrayList<Transaction>> getDoubleSpendCluster(Transaction[] possibleTxs) {
        HashMap<UTXO, ArrayList<Transaction>> spentTxos = new HashMap<UTXO, ArrayList<Transaction>>();
        for (Transaction tx: possibleTxs) {
            for (Transaction.Input in: tx.getInputs()) {
                UTXO utxo = new UTXO(in.prevTxHash, in.outputIndex);
                if (spentTxos.containsKey(utxo)) {
                    spentTxos.get(utxo).add(tx);
                }
                else {
                    ArrayList<Transaction> alTxs = new ArrayList<Transaction>();
                    alTxs.add(tx);
                    spentTxos.put(utxo, alTxs);
                }
            }
        }

        ArrayList<ArrayList<Transaction>> doubleSpendCluster = new ArrayList<ArrayList<Transaction>>();
        for (Map.Entry<UTXO, ArrayList<Transaction>> entry: spentTxos.entrySet()) {
            UTXO key = entry.getKey();
            ArrayList<Transaction> value = entry.getValue();
            if (value.size() > 1) {
                doubleSpendCluster.add(value);
            }
        }

        return doubleSpendCluster;
    }

    private HashSet<Transaction> getDoubleSpendTxs(ArrayList<ArrayList<Transaction>> doubleSpendCluster) {
        HashSet<Transaction> doubleSpendTxs = new HashSet<Transaction>();

        for (ArrayList<Transaction> txs: doubleSpendCluster) {
            for (Transaction tx: txs) {
                doubleSpendTxs.add(tx);
            }
        }

        return doubleSpendTxs;
    }

    private HashSet<Transaction> getNonDoubleSpendTxs(Transaction[] possibleTxs, HashSet<Transaction> doubleSpendTxs) {
        HashSet<Transaction> nonDoubleSpendTxs = new HashSet<Transaction>();
        for (Transaction tx: possibleTxs) {
            if (!doubleSpendTxs.contains(tx)) {
                nonDoubleSpendTxs.add(tx);
            }
        }

        return nonDoubleSpendTxs;
    }

    private Transaction[] handleTxsNonMaxFee(Transaction[] possibleTxs) {
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

    private double getTransactionFee(Transaction tx) {
        double totalInput = 0.0;
        int i = 0;
        for (Transaction.Input in: tx.getInputs()) {
            UTXO utxo = new UTXO(in.prevTxHash, in.outputIndex);

            Transaction.Output out = utxoPool.getTxOutput(utxo);
            totalInput += out.value;
        }

        double totalOutput = 0.0;
        for (Transaction.Output out: tx.getOutputs()) {
            totalOutput += out.value;
        }

        return totalInput - totalOutput;
    }

    private ArrayList<ArrayList<Transaction>> generateDoubleSpendCombinations(ArrayList<ArrayList<Transaction>> doubleSpendCluster) {
        int numberOfCombination = 1;
        for (ArrayList<Transaction> arr: doubleSpendCluster) {
            numberOfCombination *= arr.size();
        }

        int combinationLength = doubleSpendCluster.size();
        int[][] combinations = new int[numberOfCombination][combinationLength];

        int[] currentCombination = new int[combinationLength];
        for (int i = 0; i < numberOfCombination; i++) {
            int[] combination = combinations[i];

            for (int j = 0; j < combination.length; j++) {
                combination[j] = currentCombination[j];
            }

            for (int j = combination.length - 1; j >=0; j--) {
                int n = ++currentCombination[j];
                if (n < doubleSpendCluster.get(j).size()) {
                    break;
                }
                else {
                    currentCombination[j] = 0;
                }
            }
        }

        ArrayList<ArrayList<Transaction>> doubleSpendCombinations = new ArrayList<ArrayList<Transaction>>();
        for (int i = 0; i < combinations.length; i++) {
            ArrayList<Transaction> alTxs = new ArrayList<Transaction>();
            for (int j = 0; j < combinations[i].length; j++) {
                alTxs.add(doubleSpendCluster.get(j).get(combinations[i][j]));
            }
            if (alTxs.size() > 0) {
                doubleSpendCombinations.add(alTxs);
            }
        }

        return doubleSpendCombinations;
    }

    private Transaction randomSetElement(HashSet<Transaction> set) {
        int size = set.size();
        int item = (int) (Math.random() * size);
        int i = 0;
        Transaction randTx = null;
        for(Transaction tx : set)
        {
            if (i == item) {
                randTx = tx;
            }
            i++;
        }
        set.remove(randTx);

        return randTx;
    }

    public Transaction[] topologicalSort(Transaction[] txs) {
        HashMap<Transaction, ArrayList<Transaction>> incomingEdges = new HashMap<Transaction, ArrayList<Transaction>>();
        HashMap<Transaction, ArrayList<Transaction>> outcomingEdges = new HashMap<Transaction, ArrayList<Transaction>>();

        HashSet<Transaction> hashSetTxs = new HashSet<Transaction>();
        for (Transaction tx: txs) {
            hashSetTxs.add(tx);
        }

        HashMap<ByteArrayWrapperHashKey, Transaction> hashTxs = new HashMap<ByteArrayWrapperHashKey, Transaction>();
        for (Transaction tx: txs) {
            hashTxs.put(new ByteArrayWrapperHashKey(tx.getHash()), tx);
            incomingEdges.put(tx, new ArrayList<Transaction>());
        }

        for (Transaction tx: txs) {
            ArrayList<Transaction> outTxs = new ArrayList<Transaction>();
            for (Transaction.Input in: tx.getInputs()) {
                // if incoming edges tx destination is not in this txs, exclude it from the sort
                if (!hashSetTxs.contains(hashTxs.get(new ByteArrayWrapperHashKey(in.prevTxHash)))) {
                    continue;
                }

                if (incomingEdges.containsKey(in.prevTxHash)) {
                    incomingEdges.get(hashTxs.get(new ByteArrayWrapperHashKey(in.prevTxHash))).add(tx);
                }
                else {
                    ArrayList<Transaction> alTxs = new ArrayList<Transaction>();
                    alTxs.add(tx);
                    incomingEdges.put(hashTxs.get(new ByteArrayWrapperHashKey(in.prevTxHash)), alTxs);
                }
                outTxs.add(hashTxs.get(new ByteArrayWrapperHashKey(in.prevTxHash)));
            }

            outcomingEdges.put(tx, outTxs);
        }

        HashSet<Transaction> excludedTxs = new HashSet<Transaction>();
        for (Map.Entry<Transaction, ArrayList<Transaction>> entry: incomingEdges.entrySet()) {
            Transaction key = entry.getKey();
            ArrayList<Transaction> value = entry.getValue();


        }

        HashSet<Transaction> noIncomingEdges = new HashSet<Transaction>();
        for (Map.Entry<Transaction, ArrayList<Transaction>> entry: incomingEdges.entrySet()) {
            Transaction key = entry.getKey();
            ArrayList<Transaction> value = entry.getValue();

            if (value.size() == 0) {
                noIncomingEdges.add(key);
            }
        }

        ArrayList<Transaction> sortedTxs = new ArrayList<Transaction>();
        while (noIncomingEdges.size() > 0) {
            Transaction tx = randomSetElement(noIncomingEdges);
            sortedTxs.add(tx);

            for(Transaction outTx: outcomingEdges.get(tx)) {
                incomingEdges.get(outTx).remove(tx);
                if (incomingEdges.get(outTx).size() == 0) {
                    noIncomingEdges.add(outTx);
                }
            }
        }

        Transaction[] arrayOfSortedTxs = new Transaction[sortedTxs.size()];
        int i = arrayOfSortedTxs.length - 1;
        for (Transaction tx: sortedTxs) {
            arrayOfSortedTxs[i--] = tx;
        }

        return arrayOfSortedTxs;
    }

    private void printArrayListOfTransaction(ArrayList<Transaction> txs) {
        System.out.print("[");
        for (int i = 0; i < txs.size() - 1; i++) {
            System.out.print(txs.get(i).getHash() + ", ");
        }
        System.out.print(txs.get(txs.size() - 1).getHash());
        System.out.println("]");
    }

    private void printHashSetOfTransaction(HashSet<Transaction> txs) {
        System.out.print("[");
        int i = 0;
        for (Transaction tx: txs) {
            System.out.print(tx.getHash() + ", ");
            if (++i == txs.size() - 1) {
                System.out.print(tx.getHash());
                break;
            }

        }
        System.out.println("]");
    }

    private void printArrayOfTransaction(Transaction[] txs) {
        System.out.print("[");
        for (int i = 0; i < txs.length - 1; i++) {
            System.out.print(txs[i].getHash() + ", ");
        }
        System.out.print(txs[txs.length - 1].getHash());
        System.out.println("]");
    }

    private void sysoln(String str) {
        System.out.println(str);
    }

    public class ByteArrayWrapperHashKey {
        private byte[] data;

        ByteArrayWrapperHashKey(byte[] data) {
            this.data = data;
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(data);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            ByteArrayWrapperHashKey other = (ByteArrayWrapperHashKey) obj;
            if (!Arrays.equals(data, other.data))
                return false;
            return true;
        }
    }
}
