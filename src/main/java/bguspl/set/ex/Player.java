package bguspl.set.ex;

import bguspl.set.Env;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;


    protected BlockingQueue<Integer> actions;

    protected BlockingQueue<Integer> potentialSet;

    protected final Dealer dealer;

    protected boolean wonAscore;

    protected boolean needtocheck;


    protected boolean checkWasIrelevant;

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        this.actions = new LinkedBlockingQueue<>(dealer.SetSize);
        this.potentialSet = new LinkedBlockingQueue<>(dealer.SetSize);
        this.dealer = dealer;
        this.needtocheck = false;
        this.checkWasIrelevant= false;
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        if (!human) createArtificialIntelligence();

        while (!terminate) {
            // TODO implement main player loop
            //wait for key presses
            if (!actions.isEmpty()) {
                Integer slot = actions.remove(); //keyPress is going to convert to a token
                keyToToken(slot);
            }

            if (potentialSet.size() == dealer.SetSize && needtocheck) {
                synchronized (this) {
                    dealer.playersToCheck.add(id);
                    dealer.dealerThread.interrupt();
                    try {
                        this.wait();
                    } catch (InterruptedException ignore) {
                    }
                }

                result();
            }
        }
        if (!human) try {
            aiThread.join();
        } catch (InterruptedException ignored) {
        }
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");

    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {
                // TODO implement
                int randomIndex = (int) (Math.random() * (env.config.tableSize));
                keyPressed(randomIndex);
                try {
                    aiThread.sleep(10); //sets the frequency the ai presses keys
                } catch (InterruptedException ignored) {
                }
            }

            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);

        aiThread.start();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        // TODO implement
        terminate = true;
        if(!human) {
            try {
                aiThread.interrupt();
                aiThread.join();
            } catch (InterruptedException ignored) {
            }
        }
        try{
            playerThread.interrupt();
            playerThread.join();
        }catch(InterruptedException ignored){}
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        // TODO implement
        Integer newSlot = (Integer) slot;
        if (actions.size() < dealer.SetSize) {
            actions.add(newSlot);
        }
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {

        // TODO implement
        env.ui.setScore(id, ++score);

        for (long i = env.config.pointFreezeMillis; i > 0; i -= env.config.pointFreezeMillis) { //used zero as representing timeout for point freeze
            env.ui.setFreeze(id, i);
            try {
                Thread.sleep(Math.min(i, env.config.pointFreezeMillis));
            } catch (InterruptedException ignored) {
            }
        }
        env.ui.setFreeze(id, 0); //same use of 0
        actions.clear();
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests


    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {

        // TODO implement
        for (long i = env.config.penaltyFreezeMillis; i > 0; i = i - env.config.pointFreezeMillis) { //used zero as representing timeout for penalty freeze
            env.ui.setFreeze(id, i);
            try {
                Thread.sleep(Math.min(env.config.pointFreezeMillis, i));
            } catch (InterruptedException ignored) {
            }
        }
        env.ui.setFreeze(id, 0);  //same use of 0
        actions.clear();

    }

    public int score() {
        return score;
    }

    public void begin() {
        playerThread = new Thread(this);
        playerThread.start();
    }

    public void keyToToken(int slot) {
        boolean excited = table.removeToken(id, slot);
        if (!excited) {// token to put is "new"
            if (potentialSet.size() < dealer.SetSize) { //maybe already tested
                if (table.slotToCard[slot] != null) {
                    table.placeToken(id, slot);
                    potentialSet.add(slot);
                    if (potentialSet.size() == dealer.SetSize) {
                        needtocheck = true;
                    }
                }
            }
        } else {
            potentialSet.remove(slot);
        }
    }

    public void result() {
        if (checkWasIrelevant) {
            checkWasIrelevant= false;
            return;
        } else {
            if (wonAscore) {
                point();
            } else {
                penalty();
            }
            wonAscore = false;
            actions.clear();
        }
    }

}