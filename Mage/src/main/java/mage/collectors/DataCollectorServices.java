package mage.collectors;

import mage.cards.Card;
import mage.collectors.services.StaxReplayWriter;
import mage.collectors.services.PrintGameLogsDataCollector;
import mage.collectors.services.SaveGameHistoryDataCollector;
import mage.game.Game;
import mage.game.Table;
import mage.game.events.GameEvent;
import mage.players.Player;
import org.apache.log4j.Logger;

import mage.target.Target;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Not a real data collector. It's a global service to inject and collect data all around the code.
 *
 * @author JayDi85
 */
final public class DataCollectorServices implements DataCollector {

    // usage example: -Dxmage.dataCollectors.saveGameHistory=true
    private static final String COMMAND_LINE_DATA_COLLECTORS_PREFIX = "xmage.dataCollectors.";

    private static final Logger logger = Logger.getLogger(DataCollectorServices.class);

    private static DataCollectorServices instance = null;

    // fill on server startup, so it's thread safe
    Set<DataCollector> allServices = new LinkedHashSet<>();
    Set<DataCollector> activeServices = new LinkedHashSet<>();

    public static DataCollectorServices getInstance() {
        if (instance == null) {
            instance = new DataCollectorServices();
        }
        return instance;
    }

    /**
     * Init data service on server's startup
     *
     * @param enablePrintGameLogs   use for unit tests to enable additional logs for better debugging
     * @param enableSaveGameHistory use to save full game history with logs, decks, etc
     */
    public static void init(boolean enablePrintGameLogs, boolean enableSaveGameHistory) {
        if (instance != null) {
            // unit tests: init on first test run, all other will use same process
            // real server: init on server startup
            return;
        }

        // fill all possible services
        getInstance().allServices.add(new PrintGameLogsDataCollector());
        getInstance().allServices.add(new SaveGameHistoryDataCollector());
        getInstance().allServices.add(new StaxReplayWriter());
        logger.info(String.format("Data collectors: found %d services", getInstance().allServices.size()));

        // enable only needed
        getInstance().allServices.forEach(service -> {
            boolean isDefault = false;
            isDefault |= enablePrintGameLogs && service.getServiceCode().equals(PrintGameLogsDataCollector.SERVICE_CODE);
            isDefault |= enableSaveGameHistory && service.getServiceCode().equals(SaveGameHistoryDataCollector.SERVICE_CODE);
            boolean isEnable = isServiceEnable(service.getServiceCode(), isDefault);
            if (isEnable) {
                getInstance().activeServices.add(service);
            }
            String info = isEnable ? String.format(" (%s)", service.getInitInfo()) : "";
            logger.info(String.format("Data collectors: %s - %s%s", service.getServiceCode(), isEnable ? "enabled" : "disabled", info));
        });
    }

    private static boolean isServiceEnable(String dataCollectorCode, boolean isEnableByDefault) {
        String needCommand = COMMAND_LINE_DATA_COLLECTORS_PREFIX + dataCollectorCode;
        boolean isEnable;
        if (System.getProperty(needCommand) != null) {
            isEnable = System.getProperty(needCommand, "false").equals("true");
        } else {
            isEnable = isEnableByDefault;
        }
        return isEnable;
    }

    @Override
    public String getServiceCode() {
        throw new IllegalStateException("Wrong code usage. Use it by static methods only");
    }

    @Override
    public String getInitInfo() {
        throw new IllegalStateException("Wrong code usage. Use it by static methods only");
    }

    /* ------------------------------------------------------------------
     * Server / table lifecycle
     * ------------------------------------------------------------------ */

    @Override
    public void onServerStart() {
        activeServices.forEach(DataCollector::onServerStart);
    }

    @Override
    public void onTableStart(Table table) {
        activeServices.forEach(c -> c.onTableStart(table));
    }

    @Override
    public void onTableEnd(Table table) {
        activeServices.forEach(c -> c.onTableEnd(table));
    }

    /* ------------------------------------------------------------------
     * Game lifecycle
     * ------------------------------------------------------------------ */

    @Override
    public void onGameStart(Game game) {
        if (game.isSimulation()) return;
        activeServices.forEach(c -> c.onGameStart(game));
    }

    @Override
    public void onGameReady(Game game) {
        if (game.isSimulation()) return;
        activeServices.forEach(c -> c.onGameReady(game));
    }

    @Override
    public void onGameLog(Game game, String message) {
        if (game.isSimulation()) return;
        activeServices.forEach(c -> c.onGameLog(game, message));
    }

    @Override
    public void onGameEvent(Game game, GameEvent event) {
        if (game.isSimulation()) return;
        activeServices.forEach(c -> c.onGameEvent(game, event));
    }

    @Override
    public void onGameEnd(Game game) {
        if (game.isSimulation()) return;
        activeServices.forEach(c -> c.onGameEnd(game));
    }

    /* ------------------------------------------------------------------
     * Turn / step flow
     * ------------------------------------------------------------------ */

    @Override
    public void onTurnBegin(Game game) {
        if (game.isSimulation()) return;
        activeServices.forEach(c -> c.onTurnBegin(game));
    }

    @Override
    public void onTurnEnd(Game game) {
        if (game.isSimulation()) return;
        activeServices.forEach(c -> c.onTurnEnd(game));
    }

    @Override
    public void onStepBegin(Game game) {
        if (game.isSimulation()) return;
        activeServices.forEach(c -> c.onStepBegin(game));
    }

    @Override
    public void onStepEnd(Game game) {
        if (game.isSimulation()) return;
        activeServices.forEach(c -> c.onStepEnd(game));
    }

    /* ------------------------------------------------------------------
     * Player decisions
     * ------------------------------------------------------------------ */

    @Override
    public void onMulliganDecision(Game game, UUID playerId, boolean keep) {
        if (game.isSimulation()) return;
        activeServices.forEach(c -> c.onMulliganDecision(game, playerId, keep));
    }

    @Override
    public void onMulliganPutBack(Game game, UUID playerId, UUID cardId) {
        if (game.isSimulation()) return;
        activeServices.forEach(c -> c.onMulliganPutBack(game, playerId, cardId));
    }

    @Override
    public void onOpeningHandAction(Game game, UUID playerId, UUID cardId) {
        if (game.isSimulation()) return;
        activeServices.forEach(c -> c.onOpeningHandAction(game, playerId, cardId));
    }

    @Override
    public void onPlayerPass(Game game, UUID playerId) {
        if (game.isSimulation()) return;
        activeServices.forEach(c -> c.onPlayerPass(game, playerId));
    }

    @Override
    public void onChooseUse(Game game, Player player, boolean choice) {
        if (game.isSimulation()) return;
        activeServices.forEach(c -> c.onChooseUse(game, player, choice));
    }

    @Override
    public void onChooseRandom(Game game, Player player, Card card, mage.constants.ChooseContext context) {
        if (game.isSimulation()) return;
        activeServices.forEach(c -> c.onChooseRandom(game, player, card, context));
    }

    @Override
    public void onBeginActivateAbility(Game game, Player player, mage.abilities.ActivatedAbility ability) {
        if (game.isSimulation()) return;
        activeServices.forEach(c -> c.onBeginActivateAbility(game, player, ability));
    }

    @Override
    public void onEndActivateAbility(Game game, Player player, boolean success) {
        if (game.isSimulation()) return;
        activeServices.forEach(c -> c.onEndActivateAbility(game, player, success));
    }

    @Override
    public void onBeginCastSpell(Game game, Player player, mage.abilities.SpellAbility ability, mage.ApprovingObject approvingObject) {
        if (game.isSimulation()) return;
        activeServices.forEach(c -> c.onBeginCastSpell(game, player, ability, approvingObject));
    }

    @Override
    public void onEndCastSpell(Game game, Player player, boolean success) {
        if (game.isSimulation()) return;
        activeServices.forEach(c -> c.onEndCastSpell(game, player, success));
    }

    @Override
    public void onCardsRevealed(Game game, Player player, mage.cards.Cards cards) {
        if (game.isSimulation()) return;
        activeServices.forEach(c -> c.onCardsRevealed(game, player, cards));
    }

    @Override
    public void onCardsLookedAt(Game game, Player player, mage.cards.Cards cards) {
        if (game.isSimulation()) return;
        activeServices.forEach(c -> c.onCardsLookedAt(game, player, cards));
    }

    @Override
    public void onTopCardMayHaveChanged(Game game, Player player) {
        if (game.isSimulation()) return;
        activeServices.forEach(c -> c.onTopCardMayHaveChanged(game, player));
    }

    @Override
    public void onScry(Game game, Player player, java.util.List<String> cardNames) {
        if (game.isSimulation()) {
            return;
        }
        activeServices.forEach(c -> c.onScry(game, player, cardNames));
    }

    @Override
    public void onScryPutBottom(Game game, Player player, java.util.List<String> cardNames) {
        if (game.isSimulation()) {
            return;
        }
        activeServices.forEach(c -> c.onScryPutBottom(game, player, cardNames));
    }

    @Override
    public void onScryPutTop(Game game, Player player, java.util.List<String> cardNames) {
        if (game.isSimulation()) {
            return;
        }
        activeServices.forEach(c -> c.onScryPutTop(game, player, cardNames));
    }

    @Override
    public void onChoose(Game game, Player player, Target target, mage.constants.ChooseKind kind) {
        if (game.isSimulation()) return;
        activeServices.forEach(c -> c.onChoose(game, player, target, kind));
    }

    @Override
    public void onChoose(Game game, Player player, mage.choices.Choice choice, mage.constants.ChooseKind kind) {
        if (game.isSimulation()) return;
        activeServices.forEach(c -> c.onChoose(game, player, choice, kind));
    }

    @Override
    public void onMultiChoose(Game game, Player player, java.util.List<mage.choices.Choice> choices, mage.constants.ChooseKind kind) {
        if (game.isSimulation()) return;
        activeServices.forEach(c -> c.onMultiChoose(game, player, choices, kind));
    }

    /* ------------------------------------------------------------------
     * Chat
     * ------------------------------------------------------------------ */

    @Override
    public void onChatRoom(UUID roomId, String userName, String message) {
        activeServices.forEach(c -> c.onChatRoom(roomId, userName, message));
    }

    @Override
    public void onChatTourney(UUID tourneyId, String userName, String message) {
        activeServices.forEach(c -> c.onChatTourney(tourneyId, userName, message));
    }

    @Override
    public void onChatTable(UUID tableId, String userName, String message) {
        activeServices.forEach(c -> c.onChatTable(tableId, userName, message));
    }

    @Override
    public void onChatGame(UUID gameId, String userName, String message) {
        activeServices.forEach(c -> c.onChatGame(gameId, userName, message));
    }

    /* ------------------------------------------------------------------
     * Tests only
     * ------------------------------------------------------------------ */

    @Override
    public void onTestsChoiceUse(Game game, Player player, String source, String usingChoice) {
        if (game.isSimulation()) return;
        activeServices.forEach(c -> c.onTestsChoiceUse(game, player, source, usingChoice));
    }

    @Override
    public void onTestsChoiceUse(Game game, Player player, String source, Target target) {
        if (game.isSimulation()) return;
        activeServices.forEach(c -> c.onTestsChoiceUse(game, player, source, target));
    }

    @Override
    public void onTestsChoiceUse(Game game, Player player, String source, mage.cards.Card card) {
        if (game.isSimulation()) return;
        activeServices.forEach(c -> c.onTestsChoiceUse(game, player, source, card));
    }

    @Override
    public void onTestsTargetUse(Game game, Player player, String usingTarget, String reason) {
        if (game.isSimulation()) return;
        activeServices.forEach(c -> c.onTestsTargetUse(game, player, usingTarget, reason));
    }

    @Override
    public void onTestsStackPush(Game game) {
        if (game.isSimulation()) return;
        activeServices.forEach(c -> c.onTestsStackPush(game));
    }

    @Override
    public void onTestsStackResolve(Game game) {
        if (game.isSimulation()) return;
        activeServices.forEach(c -> c.onTestsStackResolve(game));
    }
}
