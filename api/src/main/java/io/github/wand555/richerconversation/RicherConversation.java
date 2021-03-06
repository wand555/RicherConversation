package io.github.wand555.richerconversation;

import io.github.wand555.richerconversation.prompts.RicherPrompt;
import io.github.wand555.richerconversation.util.MessageAndAction;
import io.github.wand555.richerconversation.util.PromptAndAnswer;
import io.github.wand555.richerconversation.util.TriConsumer;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.conversations.BooleanPrompt;
import org.bukkit.conversations.Conversable;
import org.bukkit.conversations.Conversation;
import org.bukkit.conversations.ConversationAbandonedEvent;
import org.bukkit.conversations.ConversationCanceller;
import org.bukkit.conversations.ConversationContext;
import org.bukkit.conversations.ConversationPrefix;
import org.bukkit.conversations.Prompt;
import org.bukkit.conversations.ValidatingPrompt;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The Conversation class is responsible for tracking the current state of a
 * conversation, displaying prompts to the user, and dispatching the user's
 * response to the appropriate place. Conversation objects are not typically
 * instantiated directly. Instead a {@link RicherConversationFactory} is used to
 * construct identical conversations on demand.
 * <p>
 * Conversation flow consists of a directed graph of {@link Prompt} objects.
 * Each time a prompt gets input from the user, it must return the next prompt
 * in the graph. Since each Prompt chooses the next Prompt, complex
 * conversation trees can be implemented where the nature of the player's
 * response directs the flow of the conversation.
 * <p>
 * Each conversation has a {@link ConversationPrefix} that prepends all output
 * from the conversation to the player. The ConversationPrefix can be used to
 * display the plugin name or conversation status as the conversation evolves.
 * <p>
 * Each conversation has a timeout measured in the number of inactive seconds
 * to wait before abandoning the conversation. If the inactivity timeout is
 * reached, the conversation is abandoned and the user's incoming and outgoing
 * chat is returned to normal.
 * <p>
 * You should not construct a conversation manually. Instead, use the {@link
 * RicherConversationFactory} for access to all available options.
 */
public class RicherConversation extends Conversation {

    //store again because it has private access
    private Prompt firstPrompt;

    private Map<String, MessageAndAction> goBackSequences;
    private Set<String> historySequences;
    private BaseComponentFormatter historyFormatting;
    private boolean skipNonBlocking;
    private Map<String, TriConsumer<ConversationContext, Deque<PromptAndAnswer>, Prompt>> customKeywords;

    private final Deque<PromptAndAnswer> history = new ArrayDeque<>();

    /**
     * Initializes a new Conversation.
     *
     * @param plugin The plugin that owns this conversation.
     * @param forWhom The entity for whom this conversation is mediating.
     * @param firstPrompt The first prompt in the conversation graph.
     */
    public RicherConversation(@Nullable Plugin plugin, @NotNull Conversable forWhom, @Nullable Prompt firstPrompt) {
        super(plugin, forWhom, firstPrompt);
    }

    /**
     * Initializes a new, richer Conversation.
     * @param plugin The plugin that owns this conversation.
     * @param forWhom The entity for whom this conversation is mediating.
     * @param firstPrompt The first prompt in the conversation graph
     * @param initialSessionData Any initial values to put in the conversation context sessionData map.
     * @param goBackSequences keywords which all cause to move back to the previous step in the graph.
     * @param historySequences keywords which all cause the previous questions and answers to be displayed.
     * @param historyFormatting formatting to apply for history.
     * @param customKeywords any additional keywords with a custom action following.
     */
    public RicherConversation(
            Plugin plugin,
            Conversable forWhom,
            Prompt firstPrompt,
            Map<Object, Object> initialSessionData,
            Map<String, MessageAndAction> goBackSequences,
            Set<String> historySequences,
            BaseComponentFormatter historyFormatting,
            boolean skipNonBlocking,
            Map<String, TriConsumer<ConversationContext, Deque<PromptAndAnswer>, Prompt>> customKeywords) {
        super(plugin, forWhom, firstPrompt, initialSessionData);
        this.firstPrompt = firstPrompt;
        this.goBackSequences = goBackSequences;
        this.historySequences = historySequences;
        this.historyFormatting = historyFormatting;
        this.skipNonBlocking = skipNonBlocking;
        this.customKeywords = customKeywords;
    }



    @Override
    public void abandon() {
        super.abandon();
        history.clear();
    }

    @Override
    public synchronized void abandon(ConversationAbandonedEvent details) {
        super.abandon(details);
        history.clear();
    }

    @Override
    public void outputNextPrompt() {
        if (currentPrompt == null) {
            abandon(new ConversationAbandonedEvent(this));
        } else {
            if(currentPrompt instanceof RicherPrompt richerPrompt) {
                sendMessage(richerPrompt.getRicherPromptText(context));
            }
            else {
                sendMessage(currentPrompt.getPromptText(context));
            }
            //only add non-blocking prompts to history, because prompts which require input may also be used
            //to type keywords where in that case the prompt is not added to the history
            if (!currentPrompt.blocksForInput(context)) {
                if(!skipNonBlocking) {
                    history.push(new PromptAndAnswer(currentPrompt, null));
                }
                currentPrompt = currentPrompt.acceptInput(context, null);
                outputNextPrompt();
            }
        }
    }

    /**
     * Passes player input into the current prompt. The next prompt (as determined by the current prompt) is then displayed to the user.
     * If a keyword is typed the appropriate action will take place instead.
     * @param input The user's chat text.
     */
    @Override
    public void acceptInput(String input) {
        if (currentPrompt != null) {
            // Echo the user's input
            if (localEchoEnabled && !isKeyword(input)) {
                sendMessage(input);
            }
            // Test for conversation abandonment based on input
            for (ConversationCanceller canceller : cancellers) {
                if (canceller.cancelBasedOnInput(context, input)) {
                    abandon(new ConversationAbandonedEvent(this, canceller));
                    return;
                }
            }
            if(goBackSequences.containsKey(input)) {
                goBack(goBackSequences.get(input));
                outputNextPrompt();
                return;
            }
            else if(historySequences.contains(input)) {
                formatToReadableHistory().forEach(this::sendMessage);
                outputNextPrompt();
                return;
            }
            else if(customKeywords.containsKey(input)) {
                customKeywords.get(input).accept(context, new ArrayDeque<>(history), currentPrompt);
                //name is misleading, because we're showing (possibly) the same prompt
                outputNextPrompt();
                return;
            }
            else {
                if(isValid(input)) {
                    history.push(new PromptAndAnswer(currentPrompt, input));
                }
            }
            // Not abandoned, output the next prompt
            currentPrompt = currentPrompt.acceptInput(context, input);
            outputNextPrompt();
        }
    }

    /**
     * Going back is only possible on prompts that require user input.
     * Non-input prompts are skipped when going back.
     * Impl detail: If there are only non-input prompts in the history, going back means going to the first prompt of the conversation.
     */
    private void goBack(MessageAndAction messageAndAction) {
        if(!history.isEmpty()) {
            messageAndAction.action().accept(context, history, currentPrompt);
            currentPrompt = history.pop().prompt();
            //keep going back until we find a prompt that requires input from the user, or we reach the start of the conversation
            while(!currentPrompt.blocksForInput(context) && !history.isEmpty()) {
                currentPrompt = history.pop().prompt();
            }
        }
        else {
            sendMessage(messageAndAction.message());
        }

    }

    private boolean isKeyword(String input) {
        return goBackSequences.containsKey(input) || historySequences.contains(input) || customKeywords.containsKey(input);
    }

    private boolean isValid(String input) {
        if(!(currentPrompt instanceof ValidatingPrompt)) {
            return true;
        }
        ValidatingPrompt validatingPrompt = (ValidatingPrompt) currentPrompt;
        //use reflection because isInputValid hase package access only.
        try {
            Method validMethod = ValidatingPrompt.class.getDeclaredMethod("isInputValid", ConversationContext.class, String.class);
            validMethod.setAccessible(true);
            return (boolean) validMethod.invoke(validatingPrompt, context, input);
        } catch(NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {

        }
        return false;
    }

    private List<BaseComponent> formatToReadableHistory() {
        List<BaseComponent> readableHistory = new ArrayList<>();
        history.descendingIterator().forEachRemaining(promptAndAnswer -> {
            readableHistory.add(historyFormatting.apply(promptAndAnswer, context));
        });
        return readableHistory;
    }

    private void sendMessage(BaseComponent baseComponent) {
        TextComponent toSend = new TextComponent(getPrefixMessage(), baseComponent);
        if(context.getForWhom() instanceof Player player) {
            player.spigot().sendMessage(toSend);
        }
        else {
            context.getForWhom().sendRawMessage(toSend.toPlainText());
        }
    }

    private void sendMessage(BaseComponent[] baseComponents) {
        sendMessage(new TextComponent(baseComponents));
    }

    private void sendMessage(String message) {
        sendMessage(new TextComponent(message));
    }

    private BaseComponent getPrefixMessage() {
        if(getPrefix() instanceof RicherPrefix richerPrefix) {
            return richerPrefix.getRicherPrefix(context);
        }
        else {
            return new TextComponent(getPrefix().getPrefix(context));
        }
    }

    public Prompt getCurrentPrompt() {
        return currentPrompt;
    }


}
