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
package io.agentscope.examples.werewolf.web;

import static io.agentscope.examples.werewolf.WerewolfGameConfig.HUNTER_COUNT;
import static io.agentscope.examples.werewolf.WerewolfGameConfig.MAX_DISCUSSION_ROUNDS;
import static io.agentscope.examples.werewolf.WerewolfGameConfig.MAX_ROUNDS;
import static io.agentscope.examples.werewolf.WerewolfGameConfig.SEER_COUNT;
import static io.agentscope.examples.werewolf.WerewolfGameConfig.VILLAGER_COUNT;
import static io.agentscope.examples.werewolf.WerewolfGameConfig.WEREWOLF_COUNT;
import static io.agentscope.examples.werewolf.WerewolfGameConfig.WITCH_COUNT;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.formatter.dashscope.DashScopeMultiAgentFormatter;
import io.agentscope.core.formatter.openai.OpenAIMultiAgentFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.pipeline.FanoutPipeline;
import io.agentscope.core.pipeline.MsgHub;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.werewolf.WerewolfGameConfig;
import io.agentscope.examples.werewolf.WerewolfUtils;
import io.agentscope.examples.werewolf.entity.GameState;
import io.agentscope.examples.werewolf.entity.Player;
import io.agentscope.examples.werewolf.entity.Role;
import io.agentscope.examples.werewolf.localization.GameMessages;
import io.agentscope.examples.werewolf.localization.LanguageConfig;
import io.agentscope.examples.werewolf.localization.LocalizationBundle;
import io.agentscope.examples.werewolf.localization.PromptProvider;
import io.agentscope.examples.werewolf.model.HunterShootModel;
import io.agentscope.examples.werewolf.model.SeerCheckModel;
import io.agentscope.examples.werewolf.model.VoteModel;
import io.agentscope.examples.werewolf.model.WitchHealModel;
import io.agentscope.examples.werewolf.model.WitchPoisonModel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Web-enabled Werewolf Game with event emission.
 *
 * <p>This is a modified version of WerewolfGame that emits events instead of printing to console,
 * suitable for web interface display.
 */
public class WerewolfWebGame {

    private final GameEventEmitter emitter;
    private final PromptProvider prompts;
    private final GameMessages messages;
    private final LanguageConfig langConfig;
    private final WerewolfUtils utils;
    private TTSService ttsService;
    private Map<String, String> playerVoices;
    private List<String> availableVoices;

    private OpenAIChatModel model;
    private GameState gameState;

    public WerewolfWebGame(GameEventEmitter emitter, LocalizationBundle bundle) {
        this.emitter = emitter;
        this.prompts = bundle.prompts();
        this.messages = bundle.messages();
        this.langConfig = bundle.langConfig();
        this.utils = new WerewolfUtils(messages);
        this.playerVoices = new HashMap<>();
        this.availableVoices = new ArrayList<>();
    }

    public GameState getGameState() {
        return gameState;
    }

    public void start() throws Exception {
        emitter.emitSystemMessage(messages.getInitializingGame());

        String apiKey = System.getenv("IFLOW_API_KEY");
        String baseUrl = "https://apis.iflow.cn/v1";

        model =
                OpenAIChatModel.builder()
                        .baseUrl(baseUrl)
                        .apiKey(apiKey)
                        .modelName(WerewolfGameConfig.DEFAULT_MODEL)
                        .formatter(new OpenAIMultiAgentFormatter())
                        .stream(false)
                        .build();

        // TTS Configuration
        String ttsBaseUrl = System.getenv("TTS_BASE_URL");
        if (ttsBaseUrl == null || ttsBaseUrl.isEmpty()) {
            ttsBaseUrl = baseUrl;
        }
        String ttsApiKey = System.getenv("TTS_API_KEY");
        if (ttsApiKey == null || ttsApiKey.isEmpty()) {
            ttsApiKey = apiKey;
        }

        String ttsModel = System.getenv("TTS_MODEL");
        if (ttsModel == null || ttsModel.isEmpty()) {
            ttsModel = "IndexTeam/IndexTTS-2";
        }

        // Initialize Available Voices
        String ttsVoicesEnv = System.getenv("TTS_VOICES");
        if (ttsVoicesEnv != null && !ttsVoicesEnv.isEmpty()) {
            String[] voices = ttsVoicesEnv.split(",");
            for (String v : voices) {
                if (!v.trim().isEmpty()) {
                    availableVoices.add(v.trim());
                }
            }
        } else {
            // Default voices logic
            if ("tts-1".equals(ttsModel) || "tts-1-hd".equals(ttsModel)) {
                // OpenAI standard voices
                availableVoices.add("alloy");
                availableVoices.add("echo");
                availableVoices.add("fable");
                availableVoices.add("onyx");
                availableVoices.add("nova");
                availableVoices.add("shimmer");
            } else {
                // Assume SiliconFlow or similar requiring model:voice format
                // Default voices for SiliconFlow / CosyVoice2
                String[] standardNames = {"alex", "anna", "bella", "benjamin", "charles", "claire", "david", "diana"};
                for (String name : standardNames) {
                    availableVoices.add(ttsModel + ":" + name);
                }
            }
        }
        
        // Fallback if empty
        if (availableVoices.isEmpty()) {
            availableVoices.add("alex");
        }

        ttsService = new TTSService(ttsApiKey, ttsBaseUrl, ttsModel);
        System.out.println("TTS Service initialized with Base URL: " + ttsBaseUrl + ", Model: " + ttsModel);
        System.out.println("Available Voices: " + availableVoices);

        gameState = initializeGame();
        emitStatsUpdate();

        for (int round = 1; round <= MAX_ROUNDS; round++) {
            gameState.nextRound();
            emitter.emitPhaseChange(round, "night");

            nightPhase();

            if (checkGameEnd()) {
                break;
            }

            emitter.emitPhaseChange(round, "day");
            dayPhase();

            if (checkGameEnd()) {
                break;
            }
        }

        announceWinner();
        
        // Final delay to ensure last events are flushed to client before stream closes
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private GameState initializeGame() {
        List<Role> roles = new ArrayList<>();
        for (int i = 0; i < VILLAGER_COUNT; i++) roles.add(Role.VILLAGER);
        for (int i = 0; i < WEREWOLF_COUNT; i++) roles.add(Role.WEREWOLF);
        for (int i = 0; i < SEER_COUNT; i++) roles.add(Role.SEER);
        for (int i = 0; i < WITCH_COUNT; i++) roles.add(Role.WITCH);
        for (int i = 0; i < HUNTER_COUNT; i++) roles.add(Role.HUNTER);
        Collections.shuffle(roles);

        List<Player> players = new ArrayList<>();
        List<String> playerNames = langConfig.getPlayerNames();
        
        // Define genders
        Map<String, String> nameToGender = new HashMap<>();
        // Chinese names
        nameToGender.put("潘安", "male");
        nameToGender.put("宋玉", "male");
        nameToGender.put("卫玠", "male");
        nameToGender.put("兰陵王", "male");
        nameToGender.put("唐伯虎", "male");
        nameToGender.put("貂蝉", "female");
        nameToGender.put("西施", "female");
        nameToGender.put("王昭君", "female");
        nameToGender.put("杨贵妃", "female");
        // English names
        nameToGender.put("Pan An", "male");
        nameToGender.put("Song Yu", "male");
        nameToGender.put("Wei Jie", "male");
        nameToGender.put("Prince of Lanling", "male");
        nameToGender.put("Tang Bohu", "male");
        nameToGender.put("Diaochan", "female");
        nameToGender.put("Xi Shi", "female");
        nameToGender.put("Wang Zhaojun", "female");
        nameToGender.put("Yang Guifei", "female");

        // Split available voices by gender if possible
        List<String> maleVoices = new ArrayList<>();
        List<String> femaleVoices = new ArrayList<>();
        
        for (String v : availableVoices) {
            String lowerV = v.toLowerCase();
            if (lowerV.contains("echo") || lowerV.contains("onyx") || lowerV.contains("alex") || 
                lowerV.contains("benjamin") || lowerV.contains("charles") || lowerV.contains("david")) {
                maleVoices.add(v);
            } else if (lowerV.contains("nova") || lowerV.contains("shimmer") || lowerV.contains("anna") || 
                       lowerV.contains("bella") || lowerV.contains("claire") || lowerV.contains("diana")) {
                femaleVoices.add(v);
            }
        }
        
        // Fallback if no specific voices found
        if (maleVoices.isEmpty()) maleVoices.addAll(availableVoices);
        if (femaleVoices.isEmpty()) femaleVoices.addAll(availableVoices);

        int maleCount = 0;
        int femaleCount = 0;

        for (int i = 0; i < roles.size(); i++) {
            String name = playerNames.get(i);
            Role role = roles.get(i);

            // Assign voice based on gender
            String gender = nameToGender.getOrDefault(name, "male");
            String voice;
            if ("female".equals(gender)) {
                voice = femaleVoices.get(femaleCount % femaleVoices.size());
                femaleCount++;
            } else {
                voice = maleVoices.get(maleCount % maleVoices.size());
                maleCount++;
            }
            
            playerVoices.put(name, voice);
            System.out.println("Assigned voice for " + name + " (" + gender + "): " + voice);

            ReActAgent agent =
                    ReActAgent.builder()
                            .name(name)
                            .sysPrompt(prompts.getSystemPrompt(role, name))
                            .model(model)
                            .memory(new InMemoryMemory())
                            .toolkit(new Toolkit())
                            .build();

            Player player = Player.builder().agent(agent).name(name).role(role).build();
            players.add(player);
        }

        // Emit player initialization
        List<Map<String, Object>> playersInfo = new ArrayList<>();
        for (Player player : players) {
            Map<String, Object> info = new HashMap<>();
            info.put("name", player.getName());
            info.put("role", player.getRole().name());
            info.put("roleDisplay", messages.getRoleDisplayName(player.getRole()));
            info.put("roleSymbol", messages.getRoleSymbol(player.getRole()));
            info.put("alive", true);
            playersInfo.add(info);
        }
        emitter.emitGameInit(playersInfo);

        return new GameState(players);
    }

    private void nightPhase() {
        emitter.emitSystemMessage(messages.getNightPhaseTitle());
        gameState.clearNightResults();

        Player victim = werewolvesKill();
        if (victim != null) {
            gameState.setLastNightVictim(victim);
            victim.kill();
            emitter.emitSystemMessage(messages.getWerewolvesChose(victim.getName()));
        }

        if (gameState.getWitch() != null && gameState.getWitch().isAlive()) {
            witchActions();
        }

        if (gameState.getSeer() != null && gameState.getSeer().isAlive()) {
            seerCheck();
        }

        // Emit player_eliminated events for night deaths at end of night phase
        // This ensures deaths are shown even if game ends during night
        Player nightVictim = gameState.getLastNightVictim();
        boolean wasResurrected = gameState.isLastVictimResurrected();
        if (nightVictim != null && !wasResurrected) {
            emitter.emitPlayerEliminated(
                    nightVictim.getName(),
                    messages.getRoleDisplayName(nightVictim.getRole()),
                    "killed");
        }

        emitStatsUpdate();
        emitter.emitSystemMessage(messages.getNightPhaseComplete());
    }

    private Player werewolvesKill() {
        List<Player> werewolves = gameState.getAliveWerewolves();
        if (werewolves.isEmpty()) {
            return null;
        }

        emitter.emitSystemMessage(messages.getSystemWerewolfDiscussing());

        try (MsgHub werewolfHub =
                MsgHub.builder()
                        .name("WerewolfDiscussion")
                        .participants(
                                werewolves.stream()
                                        .map(Player::getAgent)
                                        .toArray(ReActAgent[]::new))
                        .announcement(prompts.createWerewolfDiscussionPrompt(gameState))
                        .enableAutoBroadcast(true)
                        .build()) {

            werewolfHub.enter().block();

            for (int round = 0; round < 2; round++) {
                for (int i = 0; i < werewolves.size(); i++) {
                    Player werewolf = werewolves.get(i);
                    Msg response = werewolf.getAgent().call().block();
                    String content = utils.extractTextContent(response);
                    
                    String audio = null;
                    try {
                        audio = ttsService.generateAudio(content, playerVoices.get(werewolf.getName())).get();
                    } catch (Exception e) {
                        String errMsg = "TTS Generation failed for werewolf " + werewolf.getName() + ": " + e.getMessage();
                        System.err.println(errMsg);
                        emitter.emitSystemMessage("⚠️ " + errMsg);
                        
                        // Backoff if RPM limit exceeded
                        if (e.getMessage().contains("403") || e.getMessage().contains("RPM")) {
                            try {
                                System.err.println("RPM limit hit. Backing off for 10 seconds...");
                                Thread.sleep(10000);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    }
                    
                    emitter.emitPlayerSpeak(werewolf.getName(), content, "werewolf_discussion", audio);

                    // Sync with frontend playback speed: 1.5s base + 50ms per char + 1s buffer
                    int sleepTime = 1500 + (content.length() * 50) + 1000;
                    try {
                        Thread.sleep(sleepTime);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            werewolfHub.setAutoBroadcast(false);
            Msg votingPrompt = prompts.createWerewolfVotingPrompt(gameState);

            FanoutPipeline votingPipeline =
                    FanoutPipeline.builder()
                            .addAgents(
                                    werewolves.stream().map(p -> (AgentBase) p.getAgent()).toList())
                            .concurrent()
                            .build();

            List<Msg> votes;
            try {
                votes = votingPipeline.execute(votingPrompt, VoteModel.class).block();
            } catch (Exception e) {
                // Handle agent execution failures gracefully
                emitter.emitSystemMessage(messages.getErrorInDecision("werewolf voting: " + e.getMessage()));

                // In case of complete failure, broadcast result and return null to indicate no kill occurred
                emitter.emitSystemMessage(messages.getSystemWerewolfKillResult(null));
                List<Msg> broadcastMsgs = new ArrayList<>();
                broadcastMsgs.add(
                    Msg.builder()
                        .name("system")
                        .role(MsgRole.USER)
                        .content(
                            TextBlock.builder()
                                .text(messages.getSystemWerewolfKillResult(null))
                                .build())
                        .build());

                try (MsgHub werewolfHub2 =
                        MsgHub.builder()
                                .name("WerewolfVotingResult")
                                .participants(
                                        werewolves.stream()
                                                .map(Player::getAgent)
                                                .toArray(ReActAgent[]::new))
                                .build()) {
                    werewolfHub2.broadcast(broadcastMsgs).block();
                }

                return null; // Skip normal vote processing
            }

            for (int i = 0; i < votes.size(); i++) {
                Msg vote = votes.get(i);
                try {
                    VoteModel voteData = vote.getStructuredData(VoteModel.class);
                    if (voteData != null && voteData.targetPlayer != null && isValidTargetForVoting(voteData.targetPlayer, gameState)) {
                        emitter.emitPlayerVote(vote.getName(), voteData.targetPlayer, voteData.reason);
                    } else if (voteData == null) {
                        // Handle case where vote data couldn't be parsed
                        emitter.emitSystemMessage(messages.getVoteParsingError(vote.getName()));
                    } else if (!isValidTargetForVoting(voteData.targetPlayer, gameState)) {
                        // Handle case where target is invalid (e.g. dead player or self-vote)
                        emitter.emitSystemMessage(messages.getVoteParsingError(vote.getName()));
                    }
                } catch (Exception e) {
                    // Handle parsing errors gracefully
                    emitter.emitSystemMessage(messages.getVoteParsingError(vote.getName()));
                }

                // Add delay between showing votes, except for the last vote
                if (i < votes.size() - 1) {
                    try {
                        Thread.sleep(2000); // Increased delay for readability
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            Player killedPlayer = utils.countVotes(votes, gameState);

            List<Msg> broadcastMsgs = new ArrayList<>(votes);
            broadcastMsgs.add(
                    Msg.builder()
                            .name("system")
                            .role(MsgRole.USER)
                            .content(
                                    TextBlock.builder()
                                            .text(
                                                    messages.getSystemWerewolfKillResult(
                                                            killedPlayer != null
                                                                    ? killedPlayer.getName()
                                                                    : null))
                                            .build())
                            .build());
            werewolfHub.broadcast(broadcastMsgs).block();

            return killedPlayer;
        }
    }

    private void witchActions() {
        Player witch = gameState.getWitch();
        Player victim = gameState.getLastNightVictim();

        emitter.emitSystemMessage(messages.getSystemWitchActing());

        boolean usedHeal = false;

        if (witch.isWitchHasHealPotion() && victim != null) {
            try {
                emitter.emitSystemMessage(messages.getSystemWitchSeesVictim(victim.getName()));
                Msg healDecision =
                        witch.getAgent()
                                .call(prompts.createWitchHealPrompt(victim), WitchHealModel.class)
                                .block();

                WitchHealModel healModel = healDecision.getStructuredData(WitchHealModel.class);

                if (Boolean.TRUE.equals(healModel.useHealPotion)) {
                    victim.resurrect();
                    witch.useHealPotion();
                    gameState.setLastVictimResurrected(true);
                    usedHeal = true;
                    emitter.emitPlayerAction(
                            witch.getName(),
                            messages.getRoleDisplayName(Role.WITCH),
                            messages.getActionWitchUseHeal(),
                            victim.getName(),
                            messages.getActionWitchHealResult());
                    emitter.emitPlayerResurrected(victim.getName());
                } else {
                    emitter.emitPlayerAction(
                            witch.getName(),
                            messages.getRoleDisplayName(Role.WITCH),
                            messages.getActionWitchUseHeal(),
                            null,
                            messages.getActionWitchHealSkip());
                }

                // Add delay after heal action before poison action
                try {
                    Thread.sleep(1000); // Reduced delay
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            } catch (Exception e) {
                emitter.emitError(messages.getErrorWitchHeal(e.getMessage()));
            }
        }

        if (witch.isWitchHasPoisonPotion()) {
            try {
                Msg poisonDecision =
                        witch.getAgent()
                                .call(
                                        prompts.createWitchPoisonPrompt(gameState, usedHeal),
                                        WitchPoisonModel.class)
                                .block();

                WitchPoisonModel poisonModel =
                        poisonDecision.getStructuredData(WitchPoisonModel.class);

                if (Boolean.TRUE.equals(poisonModel.usePoisonPotion)
                        && poisonModel.targetPlayer != null) {
                    Player targetPlayer = gameState.findPlayerByName(poisonModel.targetPlayer);
                    if (targetPlayer != null && targetPlayer.isAlive()) {
                        targetPlayer.kill();
                        witch.usePoisonPotion();
                        gameState.setLastPoisonedVictim(targetPlayer);
                        emitter.emitPlayerAction(
                                witch.getName(),
                                messages.getRoleDisplayName(Role.WITCH),
                                messages.getActionWitchUsePoison(),
                                targetPlayer.getName(),
                                messages.getActionWitchPoisonResult());
                        emitter.emitPlayerEliminated(
                                targetPlayer.getName(),
                                messages.getRoleDisplayName(targetPlayer.getRole()),
                                "poisoned");
                    }
                } else {
                    emitter.emitPlayerAction(
                            witch.getName(),
                            messages.getRoleDisplayName(Role.WITCH),
                            messages.getActionWitchUsePoison(),
                            null,
                            messages.getActionWitchPoisonSkip());
                }
            } catch (Exception e) {
                emitter.emitError(messages.getErrorWitchPoison(e.getMessage()));
            }

            // Add delay after poison action
            try {
                Thread.sleep(1000); // Reduced delay
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        emitStatsUpdate();
    }

    private void seerCheck() {
        Player seer = gameState.getSeer();

        emitter.emitSystemMessage(messages.getSystemSeerActing());

        try {
            Msg checkDecision =
                    seer.getAgent()
                            .call(prompts.createSeerCheckPrompt(gameState), SeerCheckModel.class)
                            .block();

            SeerCheckModel checkModel = checkDecision.getStructuredData(SeerCheckModel.class);

            if (checkModel.targetPlayer != null) {
                Player target = gameState.findPlayerByName(checkModel.targetPlayer);
                if (target != null && target.isAlive()) {
                    String identity =
                            target.getRole() == Role.WEREWOLF
                                    ? messages.getIsWerewolf()
                                    : messages.getNotWerewolf();
                    emitter.emitPlayerAction(
                            seer.getName(),
                            messages.getRoleDisplayName(Role.SEER),
                            messages.getActionSeerCheck(),
                            target.getName(),
                            target.getName() + " " + identity);
                    seer.getAgent().call(prompts.createSeerResultPrompt(target)).block();
                }
            }
        } catch (Exception e) {
            emitter.emitError(messages.getErrorSeerCheck(e.getMessage()));
        }

        // Add delay after seer action
        try {
            Thread.sleep(1000); // Reduced delay
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private void dayPhase() {
        emitter.emitSystemMessage(messages.getDayPhaseTitle());

        String nightAnnouncement = prompts.createNightResultAnnouncement(gameState);
        emitter.emitSystemMessage(nightAnnouncement);

        // Night deaths are already emitted at end of nightPhase()

        Player hunter = gameState.getHunter();
        if (hunter != null
                && !hunter.isAlive()
                && (hunter.equals(gameState.getLastNightVictim())
                        || hunter.equals(gameState.getLastPoisonedVictim()))) {
            hunterShoot(hunter);
            if (checkGameEnd()) {
                return;
            }
        }

        discussionPhase();

        Player votedOut = votingPhase();

        if (votedOut != null) {
            votedOut.kill();
            String roleName = messages.getRoleDisplayName(votedOut.getRole());
            emitter.emitPlayerEliminated(votedOut.getName(), roleName, "voted");

            if (votedOut.getRole() == Role.HUNTER) {
                hunterShoot(votedOut);
            }
        }

        emitStatsUpdate();
    }

    private void discussionPhase() {
        List<Player> alivePlayers = gameState.getAlivePlayers();
        if (alivePlayers.size() <= 2) {
            return;
        }

        emitter.emitSystemMessage(messages.getSystemDayDiscussionStart());

        try (MsgHub discussionHub =
                MsgHub.builder()
                        .name("DayDiscussion")
                        .participants(
                                alivePlayers.stream()
                                        .map(Player::getAgent)
                                        .toArray(ReActAgent[]::new))
                        .announcement(
                                Msg.builder()
                                        .name("system")
                                        .role(MsgRole.USER)
                                        .content(
                                                TextBlock.builder()
                                                        .text(
                                                                prompts
                                                                        .createNightResultAnnouncement(
                                                                                gameState))
                                                        .build())
                                        .build())
                        .enableAutoBroadcast(true)
                        .build()) {

            discussionHub.enter().block();

            for (int round = 1; round <= MAX_DISCUSSION_ROUNDS; round++) {
                emitter.emitSystemMessage(messages.getDiscussionRound(round));

                if (round > 1) {
                    Msg roundPrompt = prompts.createDiscussionPrompt(gameState, round);
                    for (Player player : alivePlayers) {
                        player.getAgent().getMemory().addMessage(roundPrompt);
                    }
                }

                for (int i = 0; i < alivePlayers.size(); i++) {
                    Player player = alivePlayers.get(i);
                    Msg response = player.getAgent().call().block();
                    String content = utils.extractTextContent(response);
                    
                    String audio = null;
                    try {
                        audio = ttsService.generateAudio(content, playerVoices.get(player.getName())).get();
                    } catch (Exception e) {
                        String errMsg = "TTS Generation failed for player " + player.getName() + ": " + e.getMessage();
                        System.err.println(errMsg);
                        emitter.emitSystemMessage("⚠️ " + errMsg);

                        // Backoff if RPM limit exceeded
                        if (e.getMessage().contains("403") || e.getMessage().contains("RPM")) {
                            try {
                                System.err.println("RPM limit hit. Backing off for 10 seconds...");
                                Thread.sleep(10000);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    }

                    emitter.emitPlayerSpeak(player.getName(), content, "day_discussion", audio);

                    // Sync with frontend playback speed: 1.5s base + 50ms per char + 1s buffer
                    int sleepTime = 1500 + (content.length() * 50) + 1000;
                    try {
                        Thread.sleep(sleepTime);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    private Player votingPhase() {
        List<Player> alivePlayers = gameState.getAlivePlayers();
        if (alivePlayers.size() <= 1) {
            return null;
        }

        emitter.emitSystemMessage(messages.getSystemVotingStart());

        try (MsgHub votingHub =
                MsgHub.builder()
                        .name("DayVoting")
                        .participants(
                                alivePlayers.stream()
                                        .map(Player::getAgent)
                                        .toArray(ReActAgent[]::new))
                        .enableAutoBroadcast(true)
                        .build()) {

            votingHub.enter().block();
            votingHub.setAutoBroadcast(false);

            Msg votingPrompt = prompts.createVotingPrompt(gameState);

            FanoutPipeline votingPipeline =
                    FanoutPipeline.builder()
                            .addAgents(
                                    alivePlayers.stream()
                                            .map(p -> (AgentBase) p.getAgent())
                                            .toList())
                            .concurrent()
                            .build();

            List<Msg> votes;
            try {
                votes = votingPipeline.execute(votingPrompt, VoteModel.class).block();
            } catch (Exception e) {
                // Handle agent execution failures gracefully
                emitter.emitSystemMessage(messages.getErrorInDecision("day voting: " + e.getMessage()));

                // In case of complete failure, don't create placeholder votes that will cause parsing errors
                // Instead, return null to indicate no voting occurred
                emitter.emitSystemMessage(messages.getSystemVotingResult(null));
                List<Msg> broadcastMsgs = new ArrayList<>();
                broadcastMsgs.add(
                    Msg.builder()
                        .name("system")
                        .role(MsgRole.USER)
                        .content(
                            TextBlock.builder()
                                .text(messages.getSystemVotingResult(null))
                                .build())
                        .build());

                try (MsgHub votingHub2 =
                        MsgHub.builder()
                                .name("DayVotingResult")
                                .participants(
                                        alivePlayers.stream()
                                                .map(Player::getAgent)
                                                .toArray(ReActAgent[]::new))
                                .build()) {
                    votingHub2.broadcast(broadcastMsgs).block();
                }

                return null; // Skip normal vote processing
            }

            for (int i = 0; i < votes.size(); i++) {
                Msg vote = votes.get(i);
                try {
                    VoteModel voteData = vote.getStructuredData(VoteModel.class);
                    if (voteData != null && voteData.targetPlayer != null && isValidTargetForVoting(voteData.targetPlayer, gameState)) {
                        emitter.emitPlayerVote(vote.getName(), voteData.targetPlayer, voteData.reason);
                    } else if (voteData == null) {
                        // Handle case where vote data couldn't be parsed
                        emitter.emitSystemMessage(messages.getVoteParsingError(vote.getName()));
                    } else if (!isValidTargetForVoting(voteData.targetPlayer, gameState)) {
                        // Handle case where target is invalid (e.g. dead player or self-vote)
                        emitter.emitSystemMessage(messages.getVoteParsingError(vote.getName()));
                    }
                } catch (Exception e) {
                    // Handle parsing errors gracefully
                    emitter.emitSystemMessage(messages.getVoteParsingError(vote.getName()));
                }

                // Add delay between showing votes, except for the last vote
                if (i < votes.size() - 1) {
                    try {
                        Thread.sleep(2000); // Increased delay for readability
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            Player votedOut = utils.countVotes(votes, gameState);

            List<Msg> broadcastMsgs = new ArrayList<>(votes);
            broadcastMsgs.add(
                    Msg.builder()
                            .name("system")
                            .role(MsgRole.USER)
                            .content(
                                    TextBlock.builder()
                                            .text(
                                                    messages.getSystemVotingResult(
                                                            votedOut != null
                                                                    ? votedOut.getName()
                                                                    : null))
                                            .build())
                            .build());
            votingHub.broadcast(broadcastMsgs).block();

            return votedOut;
        }
    }

    private void hunterShoot(Player hunter) {
        emitter.emitSystemMessage(messages.getSystemHunterSkill());

        try {
            Msg shootDecision =
                    hunter.getAgent()
                            .call(
                                    prompts.createHunterShootPrompt(gameState, hunter),
                                    HunterShootModel.class)
                            .block();

            HunterShootModel shootModel = shootDecision.getStructuredData(HunterShootModel.class);

            if (Boolean.TRUE.equals(shootModel.willShoot) && shootModel.targetPlayer != null) {
                Player targetPlayer = gameState.findPlayerByName(shootModel.targetPlayer);
                if (targetPlayer != null && targetPlayer.isAlive()) {
                    targetPlayer.kill();
                    String roleName = messages.getRoleDisplayName(targetPlayer.getRole());
                    emitter.emitPlayerAction(
                            hunter.getName(),
                            messages.getRoleDisplayName(Role.HUNTER),
                            messages.getActionHunterShoot(),
                            targetPlayer.getName(),
                            messages.getActionHunterShootResult());
                    emitter.emitPlayerEliminated(targetPlayer.getName(), roleName, "shot");
                }
            } else {
                emitter.emitPlayerAction(
                        hunter.getName(),
                        messages.getRoleDisplayName(Role.HUNTER),
                        messages.getActionHunterShoot(),
                        null,
                        messages.getActionHunterShootSkip());
            }
        } catch (Exception e) {
            emitter.emitError(messages.getErrorHunterShoot(e.getMessage()));
        }

        // Add delay after hunter action
        try {
            Thread.sleep(1000); // Reduced delay
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        emitStatsUpdate();
    }

    private boolean checkGameEnd() {
        return gameState.checkVillagersWin() || gameState.checkWerewolvesWin();
    }

    private void announceWinner() {
        if (gameState.checkVillagersWin()) {
            emitter.emitGameEnd("villagers", messages.getVillagersWinExplanation());
        } else if (gameState.checkWerewolvesWin()) {
            emitter.emitGameEnd("werewolves", messages.getWerewolvesWinExplanation());
        } else {
            emitter.emitGameEnd("none", messages.getMaxRoundsReached());
        }
    }

    /**
     * Validates if the target player is valid for voting (alive and not the voter themselves).
     */
    private boolean isValidTargetForVoting(String targetPlayer, GameState gameState) {
        if (targetPlayer == null || targetPlayer.trim().isEmpty()) {
            return false;
        }

        Player target = gameState.findPlayerByName(targetPlayer);
        if (target == null) {
            return false;
        }

        // Target must be alive
        if (!target.isAlive()) {
            return false;
        }

        return true;
    }

    private void emitStatsUpdate() {
        emitter.emitStatsUpdate(
                gameState.getAlivePlayers().size(),
                gameState.getAliveWerewolves().size(),
                gameState.getAliveVillagers().size());
    }
}
