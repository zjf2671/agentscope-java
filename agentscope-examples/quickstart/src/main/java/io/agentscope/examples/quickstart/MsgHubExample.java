/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.quickstart;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.dashscope.DashScopeMultiAgentFormatter;
import io.agentscope.core.formatter.openai.OpenAIChatFormatter;
import io.agentscope.core.formatter.openai.OpenAIMultiAgentFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.pipeline.MsgHub;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.quickstart.util.MsgUtils;

/**
 * MsgHubExample - Multi-agent conversation demonstration.
 */
public class MsgHubExample {

    public static void main(String[] args) throws Exception {
        ExampleUtils.printWelcome(
                "MsgHub 示例 - 多智能体对话",
                "此示例演示如何使用 MsgHub 进行多智能体对话。\n"
                    + "三个学生（Alice、Bob 和 Charlie）将一起讨论一个话题。\n"
                    + "MsgHub 会自动将每个学生的消息广播给其他人。");

        // Get API key
        String apiKey = ExampleUtils.getIFlowApiKey();

        // Create shared model with MultiAgentFormatter
        OpenAIChatModel model = OpenAIChatModel.builder()
                .baseUrl("https://apis.iflow.cn/v1")
                .apiKey(apiKey).
                modelName("qwen3-coder-plus")
                .formatter(new OpenAIMultiAgentFormatter())
                .build();

        System.out.println("\n=== 创建三个学生智能体 ===\n");

        // Create three agents with different roles
        ReActAgent alice =
                ReActAgent.builder()
                        .name("Alice")
                        .sysPrompt(
                                "你是 Alice，一个总是看到积极面的乐观学生。"
                                        + "回答简洁（1-2句），充满热情。")
                        .model(model)
                        .memory(new InMemoryMemory())
                        .toolkit(new Toolkit())
                        .build();

        ReActAgent bob =
                ReActAgent.builder()
                        .name("Bob")
                        .sysPrompt(
                                "你是 Bob，一个注重实际问题的务实学生。"
                                    + "回答简洁（1-2句），注重实际。")
                        .model(model)
                        .memory(new InMemoryMemory())
                        .toolkit(new Toolkit())
                        .build();

        ReActAgent charlie =
                ReActAgent.builder()
                        .name("Charlie")
                        .sysPrompt(
                                "你是 Charlie，一个富有创造力、思维独特的学生。"
                                        + "回答简洁（1-2句），富有想象力。")
                        .model(model)
                        .memory(new InMemoryMemory())
                        .toolkit(new Toolkit())
                        .build();

        System.out.println(
                "已创建智能体: Alice (乐观型), Bob (务实型), Charlie" + " (创意型)\n");

        // Example 1: Basic multi-agent conversation (block style)
        basicConversationExample(alice, bob, charlie);

        // Example 2: Reactive style with Mono.then() pattern
        reactiveConversationExample(alice, bob, charlie);

        System.out.println("\n=== MsgHub 示例完成 ===");
        System.out.println(
                "\n关键要点:"
                        + "\n1. MsgHub 自动完成智能体之间的消息广播"
                        + "\n2. 使用 block() 进行同步风格的代码编写"
                        + "\n3. 使用 then() 进行完全响应式的代码编写"
                        + "\n4. 每个智能体都维护自己的对话记忆");
    }

    /** 示例 1: 带自动广播的基本多智能体对话。 */
    private static void basicConversationExample(
            ReActAgent alice, ReActAgent bob, ReActAgent charlie) {
        System.out.println("\n=== 示例 1: 基本多智能体对话 ===\n");

        // Create announcement message
        Msg announcement =
                Msg.builder()
                        .name("system")
                        .role(MsgRole.SYSTEM)
                        .content(
                                TextBlock.builder()
                                        .text(
                                                "让我们讨论一下：学习新编程语言的最佳方法是什么？"
                                                        + "每个人分享一个简短的想法。")
                                        .build())
                        .build();

        // Use MsgHub with try-with-resources for automatic cleanup
        try (MsgHub hub =
                MsgHub.builder()
                        .name("StudentDiscussion")
                        .participants(alice, bob, charlie)
                        .announcement(announcement)
                        .enableAutoBroadcast(true)
                        .build()) {

            // Enter the hub (sets up subscriptions and broadcasts announcement)
            hub.enter().block();

            System.out.println("Announcement: " + MsgUtils.getTextContent(announcement) + "\n");

            // Each agent responds in turn
            // Their responses are automatically broadcast to other participants
            System.out.println("[Alice's turn]");
            Msg aliceResponse = alice.call().block();
            printAgentResponse("Alice", aliceResponse);

            System.out.println("\n[Bob's turn]");
            Msg bobResponse = bob.call().block();
            printAgentResponse("Bob", bobResponse);

            System.out.println("\n[Charlie's turn]");
            Msg charlieResponse = charlie.call().block();
            printAgentResponse("Charlie", charlieResponse);

            // Verify message propagation
            System.out.println("\n--- 记忆验证 ---");
            System.out.println(
                    "Alice 的记忆大小: " + alice.getMemory().getMessages().size() + " 条消息");
            System.out.println(
                    "Bob 的记忆大小: " + bob.getMemory().getMessages().size() + " 条消息");
            System.out.println(
                    "Charlie 的记忆大小: "
                            + charlie.getMemory().getMessages().size()
                            + " 条消息");
            System.out.println(
                    "(每个智能体都有公告 + 所有三个回应在他们的记忆中)");
        }
        // Hub is automatically closed, subscribers are cleaned up
    }

    /**
     * 示例 2: 使用 Mono.then() 模式的响应式对话。这演示了如何使用
     * 完全响应式编程风格而不阻塞。
     */
    private static void reactiveConversationExample(
            ReActAgent alice, ReActAgent bob, ReActAgent charlie) {
        System.out.println("\n\n=== 示例 2: 使用 Mono.then() 的响应式风格 ===\n");

        // Clear memories from previous example
        alice.getMemory().clear();
        bob.getMemory().clear();
        charlie.getMemory().clear();

        Msg announcement =
                Msg.builder()
                        .name("system")
                        .role(MsgRole.SYSTEM)
                        .content(
                                TextBlock.builder()
                                        .text(
                                                "快速问题：你最喜欢的编程范式是什么？")
                                        .build())
                        .build();

        MsgHub hub =
                MsgHub.builder()
                        .name("ReactiveDiscussion")
                        .participants(alice, bob, charlie)
                        .announcement(announcement)
                        .build();

        // Fully reactive chain: enter -> alice -> bob -> charlie -> exit
        hub.enter()
                .doOnSuccess(
                        h ->
                                System.out.println(
                                        "Announcement: "
                                                + MsgUtils.getTextContent(announcement)
                                                + "\n"))
                .then(alice.call())
                .doOnSuccess(msg -> System.out.println("[Alice]: " + MsgUtils.getTextContent(msg)))
                .then(bob.call())
                .doOnSuccess(msg -> System.out.println("[Bob]: " + MsgUtils.getTextContent(msg)))
                .then(charlie.call())
                .doOnSuccess(
                        msg -> System.out.println("[Charlie]: " + MsgUtils.getTextContent(msg)))
                .then(hub.exit())
                .doOnSuccess(v -> System.out.println("\n--- 响应式链完成 ---"))
                .block(); // Only block once at the end
    }

    /** 辅助方法，用于打印智能体响应。 */
    private static void printAgentResponse(String name, Msg msg) {
        String content = MsgUtils.getTextContent(msg);
        if (content != null && !content.isEmpty()) {
            System.out.println(name + ": " + content);
        }
    }
}
