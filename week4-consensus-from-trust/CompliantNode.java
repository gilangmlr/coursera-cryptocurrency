import java.util.Set;

/* CompliantNode refers to a node that follows the rules (not malicious)*/
public class CompliantNode implements Node {
    private Set<Transaction> proposal;

    public CompliantNode(double p_graph, double p_malicious, double p_txDistribution, int numRounds) {
    }

    public void setFollowees(boolean[] followees) {
        // IMPLEMENT THIS
    }

    public void setPendingTransaction(Set<Transaction> pendingTransactions) {
        this.proposal = pendingTransactions;
    }

    public Set<Transaction> sendToFollowers() {
        return proposal;
    }

    public void receiveFromFollowees(Set<Candidate> candidates) {
        for (Candidate candidate: candidates) {
            proposal.add(candidate.tx);
        }
    }
}
