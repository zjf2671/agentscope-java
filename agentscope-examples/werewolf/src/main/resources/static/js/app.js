/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// ==================== Game State ====================
let gameRunning = false;
let players = [];
let abortController = null;
let streamFinished = false;

// ==================== Text-to-Speech State ====================
let ttsEnabled = true;
let eventQueue = [];
let isProcessingEvent = false;
let currentAudio = null;
let voiceMap = {}; // 存储玩家名到语音参数的映射

// Role icons mapping
const roleIcons = {
    'VILLAGER': '👤',
    'WEREWOLF': '🐺',
    'SEER': '🔮',
    'WITCH': '🧪',
    'HUNTER': '🏹'
};

// ==================== DOM Elements ====================
const playersGrid = document.getElementById('players-grid');
const statusCard = document.getElementById('status-card');
const statusIcon = document.getElementById('status-icon');
const statusTitle = document.getElementById('status-title');
const statusMessage = document.getElementById('status-message');
const roundInfo = document.getElementById('round-info');
const statAlive = document.getElementById('stat-alive');
const statWerewolves = document.getElementById('stat-werewolves');
const statVillagers = document.getElementById('stat-villagers');
const logContent = document.getElementById('log-content');
const startBtn = document.getElementById('start-btn');

// ==================== i18n Helper ====================
function getRoleName(role) {
    const roleNames = t('roleNames');
    return (roleNames && roleNames[role]) || role;
}

function getCauseText(cause) {
    const causeTexts = t('causeText');
    return (causeTexts && causeTexts[cause]) || cause;
}

// ==================== Game Control ====================
async function startGame() {
    if (gameRunning) return;

    startBtn.disabled = true;
    startBtn.querySelector('[data-i18n]').textContent = t('gameInProgress');

    abortController = new AbortController();
    streamFinished = false;

    try {
        const response = await fetch(`/api/game/start?lang=${currentLanguage}`, {
            method: 'POST',
            signal: abortController.signal
        });

        if (!response.ok) {
            throw new Error('Failed to start game');
        }

        gameRunning = true;
        clearLog();
        addLog(t('gameStart'), 'system');

        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';

        while (true) {
            const { done, value } = await reader.read();
            if (done) break;

            buffer += decoder.decode(value, { stream: true });
            const lines = buffer.split('\n');
            buffer = lines.pop();

            for (const line of lines) {
                if (line.startsWith('event:')) {
                    const eventType = line.substring(6).trim();
                    continue;
                }
                if (line.startsWith('data:')) {
                    const data = line.substring(5).trim();
                    if (data) {
                        try {
                            const event = JSON.parse(data);
                            handleEvent(event);
                        } catch (e) {
                            console.error('Failed to parse event:', e);
                        }
                    }
                }
            }
        }

        streamFinished = true;
        // Don't call gameEnded() here, let processEventQueue finish the queue
    } catch (error) {
        if (error.name !== 'AbortError') {
            addLog(t('connectError') + error.message, 'error');
        }
        streamFinished = true;
        gameEnded();
    }
}

function gameEnded() {
    gameRunning = false;
    if (currentAudio) {
        currentAudio.pause();
        currentAudio = null;
    }
    startBtn.disabled = false;
    startBtn.querySelector('[data-i18n]').textContent = t('playAgain');
    abortController = null;
    streamFinished = false;
}

function handleEvent(event) {
    const type = event.type;
    const data = event.data;

    // Immediate updates (Status, Stats) or specific high-priority events
    if (type === 'GAME_INIT') {
        handleGameInit(data.players);
        return;
    }
    if (type === 'STATS_UPDATE') {
        handleStatsUpdate(data.alive, data.werewolves, data.villagers);
        return;
    }
    if (type === 'ERROR') {
        addLog(t('error') + data.message, 'error');
        return;
    }

    // Queue narrative events
    eventQueue.push(event);
    processEventQueue();
}

// ==================== Event Handlers ====================
function handleGameInit(playerList) {
    players = playerList;
    renderPlayers();
    setStatus('🎮', t('gameStart'), '', '');
}

function handlePhaseChange(round, phase) {
    const phaseText = phase === 'night' ? t('phaseNight') : t('phaseDay');
    roundInfo.textContent = `${t('round')} ${round} - ${phase === 'night' ? '🌙' : '☀️'} ${phaseText}`;

    if (phase === 'night') {
        setStatus('🌙', t('nightPhase'), t('nightMessage'), 'night');
    } else {
        setStatus('☀️', t('dayPhase'), t('dayMessage'), 'day');
    }
}

function processEventQueue() {
    if (isProcessingEvent) {
        return;
    }

    if (eventQueue.length === 0) {
        if (streamFinished && gameRunning) {
            gameEnded();
        }
        return;
    }

    isProcessingEvent = true;
    const event = eventQueue.shift();
    const { type, data } = event;

    let delay = 300;

    switch (type) {
        case 'PHASE_CHANGE':
            handlePhaseChange(data.round, data.phase);
            break;
        case 'PLAYER_SPEAK':
            processPlayerSpeak(data);
            return; // processPlayerSpeak handles recursion
        case 'PLAYER_VOTE':
            handlePlayerVote(data.voter, data.target, data.reason);
            delay = 500;
            break;
        case 'PLAYER_ACTION':
            handlePlayerAction(data.player, data.role, data.action, data.target, data.result);
            delay = 500;
            break;
        case 'PLAYER_ELIMINATED':
            handlePlayerEliminated(data.player, data.role, data.cause);
            delay = 500;
            break;
        case 'PLAYER_RESURRECTED':
            handlePlayerResurrected(data.player);
            delay = 500;
            break;
        case 'SYSTEM_MESSAGE':
            addLog(data.message, 'system');
            delay = 300;
            break;
        case 'GAME_END':
            handleGameEnd(data.winner, data.reason);
            // Don't continue queue processing if game ended (handled by stopAllAudio inside handleGameEnd)
            return;
    }

    setTimeout(() => {
        isProcessingEvent = false;
        processEventQueue();
    }, delay);
}

function processPlayerSpeak(data) {
    const { player: playerName, content, context, audio } = data;

    // UI Updates
    highlightPlayer(playerName);
    const contextLabel = context === 'werewolf_discussion' ? `[${t('werewolfDiscussion')}]` : `[${t('speak')}]`;
    addLog(`<span class="speaker">[${playerName}]</span> ${contextLabel}: ${content}`, 'speak');
    console.log(`[PlayerSpeak] Processing ${playerName}. Audio: ${!!audio}`);

    // Determine duration and play audio
    let duration = 1500 + (content.length * 50); // Faster fallback: 1.5s + 50ms per char
    
    if (ttsEnabled && audio) {
        try {
            currentAudio = new Audio("data:audio/mp3;base64," + audio);
            
            currentAudio.onended = () => {
                finishEvent(playerName);
            };
            
            currentAudio.onerror = (e) => {
                console.error("Audio playback error:", e);
                // Fallback to timeout
                setTimeout(() => finishEvent(playerName), duration);
            };

            const playPromise = currentAudio.play();
            if (playPromise !== undefined) {
                playPromise.catch(e => {
                    console.error("Audio playback failed (async):", e);
                    // Fallback to timeout
                    setTimeout(() => finishEvent(playerName), duration);
                });
            }
        } catch (e) {
            console.error("Error creating audio:", e);
            setTimeout(() => finishEvent(playerName), duration);
        }
    } else {
        // No audio, use timer
        setTimeout(() => finishEvent(playerName), duration);
    }
}

function finishEvent(playerName) {
    if (playerName) {
        unhighlightPlayer(playerName);
    }
    isProcessingEvent = false;
    currentAudio = null;
    // Small buffer before next
    setTimeout(processEventQueue, 100);
}

function stopAllAudio() {
    eventQueue = [];
    isProcessingEvent = false;
    streamFinished = false;
    if (currentAudio) {
        currentAudio.pause();
        currentAudio = null;
    }
    // Clear all highlights
    document.querySelectorAll('.player-card.speaking').forEach(card => card.classList.remove('speaking'));
}

function toggleTTS() {
    ttsEnabled = !ttsEnabled;
    const btn = document.getElementById('tts-toggle');
    if (btn) {
        btn.querySelector('span').textContent = ttsEnabled ? '🔊 TTS: ON' : '🔇 TTS: OFF';
    }
}

function handlePlayerVote(voter, target, reason) {
    addLog(`[${voter}] ${t('voteFor')} ${target}（${reason}）`, 'vote');
}

function handlePlayerAction(playerName, role, action, target, result) {
    let message = `[${playerName}] (${role}) ${action}`;
    if (target) message += ` → ${target}`;
    if (result) message += `: ${result}`;
    addLog(message, 'action');
}

function handlePlayerEliminated(playerName, role, cause) {
    const causeText = getCauseText(cause);
    addLog(`💀 ${playerName} (${role}) ${causeText}`, 'eliminate');

    const player = players.find(p => p.name === playerName);
    if (player) {
        player.alive = false;
        renderPlayers();
    }
}

function handlePlayerResurrected(playerName) {
    addLog(`✨ ${playerName} ${t('resurrected')}`, 'action');

    const player = players.find(p => p.name === playerName);
    if (player) {
        player.alive = true;
        renderPlayers();
    }
}

function handleStatsUpdate(alive, werewolves, villagers) {
    statAlive.textContent = alive;
    statWerewolves.textContent = werewolves;
    statVillagers.textContent = villagers;
}

function handleGameEnd(winner, reason) {
    stopAllAudio();
    const winnerText = winner === 'villagers' ? t('villagersWin') : t('werewolvesWin');
    setStatus(winner === 'villagers' ? '🎉' : '🐺', t('gameEnd'), `${winnerText} ${reason}`, 'end');
    addLog(`${t('gameEnd')} - ${winnerText} ${reason}`, 'system');

    revealAllRoles();
}

// ==================== UI Functions ====================
function renderPlayers() {
    playersGrid.innerHTML = '';

    players.forEach(player => {
        const card = document.createElement('div');
        card.className = `player-card ${player.alive ? '' : 'dead'}`;
        card.id = `player-${player.name}`;

        const icon = player.roleSymbol || roleIcons[player.role] || '👤';
        const roleName = getRoleName(player.role) || player.roleDisplay || '???';
        const roleClass = player.role ? player.role.toLowerCase() : 'hidden';

        card.innerHTML = `
            <span class="player-icon">${icon}</span>
            <div class="player-name">${player.name}</div>
            <span class="player-role ${roleClass}">${roleName}</span>
        `;

        playersGrid.appendChild(card);
    });
}

function revealAllRoles() {
    players.forEach(player => {
        const card = document.getElementById(`player-${player.name}`);
        if (card) {
            const roleSpan = card.querySelector('.player-role');
            const roleName = getRoleName(player.role) || player.roleDisplay || '???';
            const roleClass = player.role ? player.role.toLowerCase() : 'hidden';
            roleSpan.className = `player-role ${roleClass}`;
            roleSpan.textContent = roleName;
        }
    });
}

function highlightPlayer(playerName) {
    // 先清除所有玩家的 speaking 状态，确保只有一个玩家处于 speaking 状态
    const allSpeakingCards = document.querySelectorAll('.player-card.speaking');
    allSpeakingCards.forEach(card => card.classList.remove('speaking'));

    const card = document.getElementById(`player-${playerName}`);
    if (card) {
        card.classList.add('speaking');
    }
}

function unhighlightPlayer(playerName) {
    const card = document.getElementById(`player-${playerName}`);
    if (card) {
        card.classList.remove('speaking');
    }
}

function setStatus(icon, title, message, statusClass) {
    statusIcon.textContent = icon;
    statusTitle.textContent = title;
    statusMessage.textContent = message;

    statusCard.className = 'card status-card';
    if (statusClass) {
        statusCard.classList.add(statusClass);
    }
}

function addLog(message, type = 'system') {
    const entry = document.createElement('div');
    entry.className = `log-entry ${type}`;
    entry.innerHTML = message;
    logContent.appendChild(entry);
    logContent.scrollTop = logContent.scrollHeight;
}

function clearLog() {
    logContent.innerHTML = '';
}

// ==================== Initialize ====================
document.addEventListener('DOMContentLoaded', () => {
    applyTranslations();
    updateLanguageButtons();

    const placeholderNames = t('placeholderNames') || ['1', '2', '3', '4', '5', '6', '7', '8', '9'];
    players = placeholderNames.map(name => ({
        name: name,
        role: null,
        roleDisplay: '???',
        roleSymbol: '👤',
        alive: true
    }));
    renderPlayers();
});
