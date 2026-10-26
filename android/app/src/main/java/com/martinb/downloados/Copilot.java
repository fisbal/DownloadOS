package com.martinb.downloados;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;

import java.util.stream.Collectors;

/** The built-in Claude copilot: answers questions about installing and updating MartinB OS apps. */
final class Copilot {
    private Copilot() {}

    static final String MODEL = "claude-opus-5-5";

    private static final String SYSTEM =
            "You are the help assistant inside DownloadOS, an Android app that installs and updates "
                    + "the APKs published in the GitHub Releases of one account (MartinB OS apps such as "
                    + "Office In 1, ProDFOS, MarbleOS). It also installs from a pasted link or an .apk file. "
                    + "Help the user fix install, update and permission problems on their phone. "
                    + "Give short, numbered steps they can follow on the phone. If an install fails with a "
                    + "signature conflict, explain that the installed copy was signed with another key and must "
                    + "be uninstalled once. Answer in the user's language.";

    /** Blocking call: run it off the UI thread. */
    static String ask(String apiKey, String phoneContext, String question, boolean french) {
        AnthropicClient client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
        MessageCreateParams params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(4000L)
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .system(SYSTEM)
                .addUserMessage("This phone:\n" + phoneContext + "\n"
                        + (french ? "(The user writes French.)\n" : "") + "Question: " + question)
                .build();
        Message response = client.messages().create(params);
        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(textBlock -> textBlock.text())
                .collect(Collectors.joining("\n"))
                .trim();
    }
}
