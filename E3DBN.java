import org.rlcommunity.rlglue.codec.taskspec.TaskSpec;
import org.rlcommunity.rlglue.codec.types.Action;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An implementation of the E3 algorithm in a discounted factored MDP.
 */
public class E3DBN {

    private class AllActions implements AllActionsGetter {

        Map<List<List<Integer>>, List<List<Integer>>> cache = new HashMap<>();

        private List<List<Integer>> fullActions(List<List<Integer>> input) {
            List<List<Integer>> returnList = cache.get(input);

            if (returnList != null) {
                return returnList;
            }

            int max = 1;
            int[] sizes = new int[input.size()];
            int k = 0;
            for (List<Integer> l : input) {
                max *= l.size();
                sizes[k++] = l.size();
            }
            returnList = new ArrayList<>(max);
            for (int i = 0; i < max; i++) {
                int iCpy = i;
                List<Integer> subList = new ArrayList<>(input.size());
                for (int j = 0; j < input.size(); j++) {
                    subList.add(input.get(j).get(iCpy % sizes[j]));
                    iCpy = iCpy / sizes[j];
                }
                returnList.add(subList);
            }
            cache.put(input, returnList);
            return returnList;
        }

        @Override
        public List<Action> getAllActions(List<PartialState> state) {
            List<Action> actions = new ArrayList<>();
            List<List<Integer>> partialActions = new ArrayList<>(state.size());
            for (PartialState partial : state) {
                partialActions.add(partial.possibleActions());
            }
            for (List<Integer> actints : fullActions(partialActions)) {
                Action act = new Action(E3Agent.NBR_REACHES, 0, 0);

                for (int i = 0; i < E3Agent.NBR_REACHES; i++) {
                    act.setInt(i, actints.get(i));
                }

                actions.add(act);
            }

            return actions;
        }

    }

    private AllActions allActions;
    private double discount;
    private long horizonTime;
    private double maxReward;
    private final int partialStateKnownLimit = 10;

    //mostly for debugging
    private int balancingCount = 0;
    double chanceToExplore;

    // State, Action, State -> Visits
    private Map<List<PartialState>, Integer> stateVisits;

    public PartialTransitionProbabilityLogger ptpl;

    // State -> Reward
    private Map<List<PartialState>, Double> rewards;

    // Current policy
    private Map<List<PartialState>, Action> currentPolicy;

    // Current time we have expl*
    private long currentTime;


    // This is the absorbing state which represents all unvisited states
    private List<PartialState> dummyState;

    public enum SimulatorState {
        Frozen, UnFrozen
    }

    private SimulatorState simulatorState;

    /**
     * @param discount discount factor
     * @param eps the epsilon parameter
     * @param maxReward the maximum possible reward
     * @param taskspec the taskspec
     * @param dummyState representation of the absorbing dummystate
     */
    public E3DBN(
            double discount,
            double eps,
            double maxReward,
            TaskSpec taskspec,
            List<PartialState> dummyState) {

        stateVisits = new HashMap<>();

        String edges = taskspec.getExtraString();

        DiGraph graph = DiGraph.graphFromString(edges);
        System.out.println(edges);
        for (int i = 0; i < graph.edges().size(); i++) {
            if (graph.edges().get(i).contains(E3Agent.NBR_REACHES)) {
                //MOAAHAHAHAHAHAHAHAHA
                graph.edges().get(i).remove(graph.edges().get(i).indexOf(E3Agent.NBR_REACHES));
            }
        }
        System.out.println(graph.edges());

        allActions = new AllActions();
        ptpl = new PartialTransitionProbabilityLogger(
                graph.edges(), partialStateKnownLimit,
                allActions);


        rewards = new HashMap<>();

        // This is the absorbing state which represents all unvisited states
        this.dummyState = dummyState;

        this.maxReward = maxReward;

        this.discount = discount;
        horizonTime = Math.round((1 / (1 - discount)));
    }    
   /* private Map<Integer, List<Integer>> parseConnectionsMessage(
            String responseMessage) {
        Map<Integer, List<Integer>> returnMap = new HashMap<>();
        String[] connections = responseMessage.split(":");
        for (int i = 0; i < connections.length; i++) {
            List<Integer> list = new ArrayList<Integer>();
            for (String nr : connections[i].split("\\s+")) {
                int a = Integer.parseInt(nr);
                list.add(a);
            }
            returnMap.put(i, list);
        }
        return returnMap;
    }*/

    @SuppressWarnings("unused")
    private void l(Object obj) {
        System.out.println(obj);
    }


    // Picking the next action {{{

    public String policy = "";

    /**
     * Find the next action.
     */
    public Action nextAction(List<PartialState> state) {
        if (simulatorState == SimulatorState.Frozen) {
            if (ptpl.isKnown(state)) {
                policy = "frozen policy";
                return currentPolicy.get(state);
            } else {
                policy = "frozen balancing";
                return balancedWandering(state);
            }
        } else {

            // Unknown state
            if (!ptpl.isKnown(state)) {
                policy = "balancing";
                currentPolicy = null;
                currentTime = 0;
                balancingCount++;
                return balancedWandering(state);
            }

            // If expl* long enough: balanced wandering
            if (currentTime >= horizonTime) {
                currentPolicy = null;
                currentTime = 0;
            }

            // Start exploitation/exploration (the second condition is there
            // to deal with states becoming known while expl*ing.)
            if (currentPolicy == null || currentPolicy.get(state) == null) {
                currentPolicy = findExplorationPolicy();
                policy = "exploration";

                // Should we really explore?
                if (!shouldExplore(currentPolicy, state)) {
                    currentPolicy = findExploitationPolicy();
                    policy = "exploitation";
                }
            }

            currentTime++;
            return currentPolicy.get(state);
        }
    }

    /**
     * Find the balanced wandering action
     */
    private Action balancedWandering(List<PartialState> state) {
        Action balancingAction = null;

        int lowest = Integer.MAX_VALUE;
        for (Action action : allActions.getAllActions(state)) {
            if (balancingAction == null) {
                lowest = ptpl.getSmallestPartialStateActionVisitCount(action, state);
                balancingAction = action;
                continue;
            }
            int temp = ptpl.getSmallestPartialStateActionVisitCount(action, state);
            if (temp < lowest) {
                lowest = temp;
                balancingAction = action;
            }
        }

        return balancingAction;
    }


    /**
     * Should we explore given some policy?
     */
    private boolean shouldExplore(Map<List<PartialState>, Action> explorationPolicy,
                                  List<PartialState> currentState) {
        Map<List<PartialState>, Double> probs = new HashMap<>();

        // Starting probabilities are 0 for known states and 1 for unknown
        for (List<PartialState> state : ptpl.getObservedStates()) {
            if (ptpl.isKnown(state)) {
                probs.put(state, 0.0);
            } else {
                probs.put(state, 1.0);
            }
        }

        // Find probability that we end up in an unknown state in horizonTime steps
        for (long i = 0; i < horizonTime; i++) {
            for (List<PartialState> state : ptpl.getObservedStates()) {
                if (ptpl.isKnown(state)) {
                    double probability = 0;

                    // For all possible transitions, multiply transition
                    // probability by the stored value for the target state. 
                    List<List<PartialState>> nextStates =
                            ptpl.statesFromPartialStates(state, explorationPolicy.get(state));
                    for (List<PartialState> nextState : nextStates) {
                        Double d = probs.get(nextState);
                        d = d == null ? 1 : d;
                        probability += d * ptpl.getProbability(state,
                                explorationPolicy.get(state), nextState);
                    }
                    probs.put(state, probability);
                }
            }
        }

        // TODO: 0.05 should be epsilon / (2 * G^T_max) (see paper, section 6)
        chanceToExplore = probs.get(currentState);
        return probs.get(currentState) > 0.05;
    }

    // }}}

    // Policy calculations {{{

    /**
     * Performs value iteration in to find a policy that explores new states within horizonTime
     */
    private Map<List<PartialState>, Action> findExplorationPolicy() {
        return findPolicy(true);
    }

    /**
     * Performs value iteration in to find a policy that exploits new states within horizonTime
     */
    private Map<List<PartialState>, Action> findExploitationPolicy() {
        return findPolicy(false);
    }

    private Map<List<PartialState>, Action> findPolicy(boolean explorationPolicy) {
        // Value function
        Map<List<PartialState>, Double> vf = new HashMap<>();

        // Policy
        Map<List<PartialState>, Action> policy = new HashMap<>();

        // Initialize value function
        for (List<PartialState> state : ptpl.getObservedStates()) {
            if (ptpl.isKnown(state)) {
                vf.put(state, 0.0);
            }
        }

        vf.put(dummyState, 0.0);
        setReward(dummyState, explorationPolicy ? maxReward : 0.0);

        for (long t = 0; t < horizonTime; t++) {
            for (List<PartialState> state : vf.keySet()) {
                double bestValue = Double.NEGATIVE_INFINITY;
                Action bestAction = null;

                if (state.equals(dummyState)) {
                    vf.put(state, getReward(state) + discount * vf.get(state));

                    continue;
                }

                for (Action action : allActions.getAllActions(state)) {
                    double currentValue = 0;

                    for (List<PartialState> o : ptpl.getObservedStates()) {

                        if (vf.keySet().contains(o) && !dummyState.equals(o)) {
                            currentValue +=
                                    ptpl.getProbability(state, action, o) * vf.get(o);
                        } else if (explorationPolicy) {
                            currentValue +=
                                    ptpl.getProbability(state, action, o) * vf.get(dummyState);
                        } else {
                            // Do nothing if exploitation policy and state is unknown
                        }
                    }

                    if (currentValue > bestValue) {
                        bestValue = currentValue;
                        bestAction = action;
                    }
                }

                vf.put(state, getReward(state) + discount * bestValue);
                policy.put(state, bestAction);
            }
        }
        return policy;
    }

    // }}}

    // Updating necessary statistics {{{

    /**
     * Observe a state transition.
     */
    public void observe(
            List<PartialState> from,
            Action action,
            List<PartialState> to,
            double reward
    ) {

        // Log the visit
        updateVisits(from, action, to);

        // Update reward table
        updateReward(to, reward);

        ptpl.record(from, action, to);
    }

    /**
     * Update visits statistics
     */
    private void updateVisits(List<PartialState> from, Action action,
                              List<PartialState> to) {
        // State visits
        stateVisits.put(from, stateVisits.containsKey(from) ? stateVisits.get(from) + 1 : 1);

    }


    /**
     * Update reward table.
     */
    private void updateReward(List<PartialState> to, double reward) {
        int visits = getVisits(to);

        double oldReward = 0;

        if (rewards.containsKey(to)) {
            oldReward = rewards.get(to);
        }

        rewards.put(to, (reward + oldReward * visits) / (visits + 1));
    }

    /**
     * Update reward table.
     */
    private void setReward(List<PartialState> to, double reward) {
        rewards.put(to, reward);
    }

    // }}}

    // Get state, action, state visit statistics {{{

    private int getVisits(List<PartialState> state) {
        return stateVisits.containsKey(state) ? stateVisits.get(state) : 0;
    }

    private double getReward(List<PartialState> state) {
        return rewards.get(state);
    }

    public int getKnownCount() {
        return ptpl.knownCount;
    }

    public int getBalancingActionCount() {
        return balancingCount;
    }

    public double getChanceToExplore() {
        return chanceToExplore;
    }

    // }}}

    // {{{ Simulator shiz

    public void freezePolicy() {
        this.simulatorState = SimulatorState.Frozen;
        currentPolicy = findExploitationPolicy();
    }

    public void unFreezePolicy() {
        this.simulatorState = SimulatorState.UnFrozen;
    }

    // }}}

}
