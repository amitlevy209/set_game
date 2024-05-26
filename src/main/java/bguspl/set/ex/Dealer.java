package bguspl.set.ex;
import bguspl.set.Config;
import bguspl.set.Env;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.Collections;



/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    // added fields
    public BlockingQueue<Integer> playersToCheck;

    private LinkedList<Integer> cardsToRemove;
    protected   Thread dealerThread;

    public final int SetSize=3;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        cardsToRemove = new LinkedList<Integer>();
        playersToCheck= new LinkedBlockingQueue<Integer>();
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        this.dealerThread = Thread.currentThread();
        env.ui.setCountdown(env.config.turnTimeoutMillis,false); //first time countdown appear
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");

        //First step create player threads
        for (int i=0; i<players.length; i++)

        { //zero for loop
            players[i].begin();
        }

        while (!shouldFinish()) {
            placeCardsOnTable();
            timerLoop();
            updateTimerDisplay(false);
            removeAllCardsFromTable();
        }
        announceWinners();
        terminate();
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        env.ui.setCountdown(env.config.turnTimeoutMillis,false); //first time count starts
        reshuffleTime=System.currentTimeMillis()+env.config.turnTimeoutMillis;

        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            //  table.hints(); //we use for testing our code
            updateTimerDisplay(false);
            removeCardsFromTable();
            placeCardsOnTable();
        }
    }
    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        // TODO implement
        for (Player player:players){
            player.terminate();
        }
        terminate= true;
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        synchronized (table) {
            // TODO implement
            if (!cardsToRemove.isEmpty()) {
                for (int slot : cardsToRemove) {
                    table.removeCard(slot);

                    for (Player player:players){
                        if (player.potentialSet.contains(slot)){
                            player.potentialSet.remove(slot);
                        }
                    }

                }
                cardsToRemove.clear();
            }
        }
    }
    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        // TODO implement
        //first step, shuffle the cards as asked:
        Collections.shuffle(deck);
        synchronized (table) {

            //second step, place cards on available tokens
            boolean needToPlace = true;
            while (!deck.isEmpty() && needToPlace) {
                int toAdd = env.config.tableSize - table.countCards();
                if (toAdd == 0) { //use zero to find if there is no cards to place
                    needToPlace = false;
                }

                if (needToPlace) {
                    List<Integer> tokenIndexToPlace = new ArrayList<>();
                    for (int i = 0; i < table.slotToCard.length; i ++) {
                        if (table.slotToCard[i] == null) {
                            tokenIndexToPlace.add(i);
                        }
                    }
                    Collections.shuffle(tokenIndexToPlace);
                    //use of 0 in this loop, because we shuffle the lists therefore we can use the first element and remove it each time, to make it random as needed
                    for (int i = 0; i < toAdd; i ++) {
                        table.placeCard(deck.get(0), tokenIndexToPlace.get(0));
                        deck.remove(deck.get(0));
                        tokenIndexToPlace.remove((tokenIndexToPlace.get(0)));
                    }

                    needToPlace = false;
                }
            }
        }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private  void sleepUntilWokenOrTimeout() {
        // TODO implement
        try {
            if (reshuffleTime-System.currentTimeMillis()<5000) {
                Thread.sleep(10); // when countdown is warn timer should be update each 10 second
            }
            else{
                Thread.sleep(800);//we would like it to sleep but still update counting each second. 800 is a second but calculate time for actions...(-200+/-)
            }} catch (InterruptedException e) {

            // check all the players that want that the dealer will check them
            while (!playersToCheck.isEmpty()) {
                int playerId = playersToCheck.remove();
                synchronized (players[playerId]) {
                    Player playerChecked = players[playerId];

                    // if the player potential set is in size 3
                    if (playerChecked.potentialSet.size() == SetSize) {
                        int[] playerCards = new int[SetSize];
                        int i = 0;
                        for (int card : playerChecked.potentialSet) {
                            playerCards[i] = table.slotToCard[card];
                            i ++;
                        }

                        // if the cards are sets
                        if (env.util.testSet(playerCards)) {
                            playerChecked.wonAscore = true;
                            playerChecked.potentialSet.clear();
                            for (int j = 0; j < SetSize; j++) { // add all the cards that need to be removed from the table
                                cardsToRemove.add(table.cardToSlot[playerCards[j]]);
                            }
                            removeCardsFromTable(); //הוספנו
                            placeCardsOnTable();
                            updateTimerDisplay(true);
                        }
                        // if the cards aren't a set
                        else {
                            playerChecked.wonAscore = false;
                        }
                        players[playerId].needtocheck = false;
                        players[playerId].notifyAll();
                    }
                    //if the player potential set is not at size 3
                    else {
                        players[playerId].notifyAll();
                        players[playerId].checkWasIrelevant= true;
                    }
                }
            }
        }
    }


    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        // TODO implement
        if (reset) {
            reshuffleTime = System.currentTimeMillis() + (env.config.turnTimeoutMillis);
            env.ui.setCountdown(env.config.turnTimeoutMillis, false);
        }
        else {
            if (reshuffleTime-System.currentTimeMillis() <=5000 ) {
                env.ui.setCountdown(reshuffleTime-System.currentTimeMillis(), true);
            }
            else {
                env.ui.setCountdown(reshuffleTime - System.currentTimeMillis(), false);
            }
        }
    }
    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        // TODO implement
        synchronized (table) {
            boolean needToRemove = true;
            int toRemove = table.countCards();

            if (toRemove == 0) {
                needToRemove = false;
            }

            List<Integer> tokenIndexToRemove = new ArrayList<>();
            for (int i = 0; i < table.slotToCard.length; i++) {
                if (table.slotToCard[i] != null) {
                    tokenIndexToRemove.add(i);
                }
            }
            Collections.shuffle(tokenIndexToRemove);
            for (int i = 0; i < toRemove; i ++) { //use of 0 in this loop, because we shuffel the lists therefore we can use the first element and remove it each time, to make it shuffel as needed

                int selectedSlot = tokenIndexToRemove.get(0);
                deck.add(table.slotToCard[selectedSlot]);
                table.removeCard(selectedSlot);
                tokenIndexToRemove.remove((tokenIndexToRemove.get(0)));
            }

            needToRemove = false;
            playersToCheck.clear();
            for (Player player : players) {
                player.potentialSet.clear();
                player.actions.clear();

            }
            cardsToRemove.clear();
        }
    }
    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        // TODO implement

        int maxScore=0;  //first minimum value
        int countingWinners=0; //first time counting winners
        for (Player mightWin: players) {
            if (mightWin.score() > maxScore) {
                maxScore = mightWin.score();
                countingWinners = 1;
            } else if (mightWin.score() == maxScore) {
                countingWinners ++;
            }
        }

        int [] winners= new int[countingWinners];
        int i=0;

        for (Player mightWin: players){
            if (mightWin.score()== maxScore) {
                winners[i]= mightWin.id;
                i++;
            }
        }
        env.ui.announceWinner(winners);

    }
}