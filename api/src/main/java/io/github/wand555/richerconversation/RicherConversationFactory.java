package io.github.wand555.richerconversation;

import com.google.common.collect.ImmutableSet;
import io.github.wand555.richerconversation.util.PromptAndAnswer;
import io.github.wand555.richerconversation.util.TriConsumer;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.conversations.Conversable;
import org.bukkit.conversations.Conversation;
import org.bukkit.conversations.ConversationAbandonedListener;
import org.bukkit.conversations.ConversationCanceller;
import org.bukkit.conversations.ConversationContext;
import org.bukkit.conversations.ConversationFactory;
import org.bukkit.conversations.ConversationPrefix;
import org.bukkit.conversations.Prompt;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * A ConversationFactory is responsible for creating a {@link RicherConversation} from a predefined template.
 * A RicherConversationFactory is typically created when a plugin is instantiated and builds a {@link RicherConversation}
 * each time a user initiates a conversation with the plugin. Each {@link RicherConversation}
 * maintains its own state and calls back as needed into the plugin.
 * The {@link RicherConversationFactory} implements a fluid API, allowing parameters to be set as an extension to the constructor.
 */
public class RicherConversationFactory extends ConversationFactory {

    private BaseComponent cantGoBackMessage = new TextComponent("Cannot go back further!");
    private final Set<String> goBackSequences = new HashSet<>();
    private final Set<String> showHistorySequences = new HashSet<>();
    private BaseComponentFormatter historyFormatting = (promptAndAnswer, context) -> new TextComponent("Q: " + promptAndAnswer.prompt().getPromptText(context) + " A: " + promptAndAnswer.answer());
    private Map<String, TriConsumer<ConversationContext, Deque<PromptAndAnswer>, Prompt>> customKeywords = new HashMap<>();

    /**
     * Constructs a ConversationFactory.
     *
     * @param plugin The plugin that owns the factory.
     */
    public RicherConversationFactory(Plugin plugin) {
        super(plugin);
    }

    /**
     * Sets the player input that, when received, will cause the conversation flow to move to the previous step.
     * @param goBackSequence Input to trigger the effect.
     * @return This object.
     */
    public RicherConversationFactory withGoBack(String goBackSequence) {
        return withGoBack(goBackSequence, cantGoBackMessage);
    }

    /**
     * Sets the player input that, when received, will cause the conversation flow to move to the previous step.
     * @param goBackSequence Input to trigger the effect.
     * @param cantGoBackMessage Message to be displayed if the beginning of the conversation is reached and therefore cannot go back any further.
     * @return This object.
     */
    public RicherConversationFactory withGoBack(String goBackSequence, String cantGoBackMessage) {
        return withGoBack(goBackSequence, new TextComponent(cantGoBackMessage));
    }

    /**
     * Sets the player input that, when received, will cause the conversation flow to move to the previous step.
     * @param goBackSequence Input to trigger the effect.
     * @param cantGoBackMessage Message to be displayed if the beginning of the conversation is reached and therefore cannot go back any further.
     * @return This object.
     */
    public RicherConversationFactory withGoBack(String goBackSequence, BaseComponent cantGoBackMessage) {
        goBackSequences.add(goBackSequence);
        this.cantGoBackMessage = cantGoBackMessage;
        return this;
    }

    /**
     * Sets the player input that, when received, will display the entire previous history.
     * Typing this keyword won't have any effect on the current prompt. It behaves like no answer was given.
     * @param showHistorySequence Input to trigger the effect.
     * @return This object.
     */
    public RicherConversationFactory withShowHistory(String showHistorySequence) {
        return withShowHistory(showHistorySequence, historyFormatting);
    }

    /**
     * Sets the player input that, when received, will display the entire previous history.
     * Typing this keyword won't have any effect on the current prompt. It behaves like no answer was given.
     * @param showHistorySequence Input to trigger the effect.
     * @param formatting Function to format each line in the history with.
     * @return This object.
     */
    public RicherConversationFactory withShowHistory(String showHistorySequence, StringFormatter formatting) {
        return withShowHistory(showHistorySequence, (BaseComponentFormatter) (promptAndAnswer, context) -> new TextComponent(formatting.apply(promptAndAnswer, context)));
    }

    /**
     * Sets the player input that, when received, will display the entire previous history.
     * Typing this keyword won't have any effect on the current prompt. It behaves like no answer was given.
     * @param showHistorySequence Input to trigger the effect.
     * @param formatting Function to format each line in the history with.
     * @return This object.
     */
    public RicherConversationFactory withShowHistory(String showHistorySequence, BaseComponentFormatter formatting) {
        showHistorySequences.add(showHistorySequence);
        this.historyFormatting = formatting;
        return this;
    }

    /**
     * Sets a player input that, when received, will trigger a custom action.
     * The action consumer provides the {@link ConversationContext}, the entire history and the current prompt that is displayed
     * while the keyword was typed.
     * <p></p>
     * The history stack is a copy of the underlying history. Any changes to this copy are NOT reflected in the actual history.
     * <p></p>
     * An important note for the history: The current prompt is not present in the history stack. So for example by removing the top
     * element from the stack you actually remove the previous prompt from the history, not the currently displayed one.
     * @param customKeyword Input to trigger the action.
     * @param action Action to be performed after the keyword is typed.
     * @return This object.
     */
    public RicherConversationFactory withCustomKeyword(String customKeyword, TriConsumer<ConversationContext, Deque<PromptAndAnswer>, Prompt> action) {
        customKeywords.put(customKeyword, action);
        return this;
    }

    @Override
    public Conversation buildConversation(Conversable forWhom) {
        //Abort conversation construction if we aren't supposed to talk to non-players
        if (playerOnlyMessage != null && !(forWhom instanceof Player)) {
            return new Conversation(plugin, forWhom, new Prompt() {
                @Override
                public String getPromptText(ConversationContext context) {
                    return playerOnlyMessage;
                }

                @Override
                public boolean blocksForInput(ConversationContext context) {
                    return false;
                }

                @Override
                public Prompt acceptInput(ConversationContext context, String input) {
                    return END_OF_CONVERSATION;
                }
            });
        }

        //Clone any initial session data
        Map<Object, Object> copiedInitialSessionData = new HashMap<>();
        copiedInitialSessionData.putAll(initialSessionData);

        //Build and return a conversation
        RicherConversation richerConversation = new RicherConversation(
                plugin,
                forWhom,
                firstPrompt,
                copiedInitialSessionData,
                goBackSequences,
                cantGoBackMessage,
                showHistorySequences,
                historyFormatting,
                customKeywords);

        //use reflection because the methods are made private (for no apparent reason, why would the variables be protected then?)
        try {
            Method modalMethod = Conversation.class.getDeclaredMethod("setModal", boolean.class);
            modalMethod.setAccessible(true);
            modalMethod.invoke(richerConversation, isModal);
        } catch(NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {

        }
        //richerConversation.setModal(isModal);

        richerConversation.setLocalEchoEnabled(localEchoEnabled);

        try {
            Method prefixMethod = Conversation.class.getDeclaredMethod("setPrefix", ConversationPrefix.class);
            prefixMethod.setAccessible(true);
            prefixMethod.invoke(richerConversation, prefix);
        } catch(NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {

        }

        //richerConversation.setPrefix(prefix);

        //Clone the conversation cancellers
        for (ConversationCanceller canceller : cancellers) {
            try {
                Method convCancellerMethod = Conversation.class.getDeclaredMethod("addConversationCanceller", ConversationCanceller.class);
                convCancellerMethod.setAccessible(true);
                convCancellerMethod.invoke(richerConversation, canceller);
            } catch(NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {

            }

            //richerConversation.addConversationCanceller(canceller.clone());
        }

        //Add the ConversationAbandonedListeners
        for (ConversationAbandonedListener listener : abandonedListeners) {
            richerConversation.addConversationAbandonedListener(listener);
        }

        return richerConversation;
    }
}
