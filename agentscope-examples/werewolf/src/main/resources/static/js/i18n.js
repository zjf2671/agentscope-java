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

const i18n = {
    'zh_CN': {
        gameTitle: '狼人杀对决',
        gameSubtitle: '由 AgentScope 驱动',
        waitingToStart: '等待开始',
        clickStartMessage: '点击开始游戏按钮开始一场惊心动魄的博弈。',
        startGame: '开始游戏',
        gameInProgress: '对局进行中...',
        playAgain: '再来一局',
        statAlive: '存活人数',
        statWerewolves: '狼人阵营',
        statVillagers: '好人阵营',
        playersList: '参与玩家',
        gameLog: '游戏日志',
        welcomeMessage: '欢迎来到 狼人杀！准备好开始了吗？',
        round: '回合',
        phaseNight: '夜晚',
        phaseDay: '白天',
        gameStart: '对局开始！',
        startFailed: '启动失败: ',
        connectError: '连接错误: ',
        gameEnd: '游戏结束',
        villagersWin: '🎉 村民阵营获胜！',
        werewolvesWin: '🐺 狼人阵营获胜！',
        error: '错误: ',
        nightPhase: '夜晚阶段',
        nightMessage: '请闭眼...狼人请睁眼',
        dayPhase: '白天阶段',
        dayMessage: '请睁眼...开始讨论',
        werewolfDiscussion: '狼人密谋',
        speak: '发言',
        voteFor: '投票给',
        roleNames: {
            VILLAGER: '村民',
            WEREWOLF: '狼人',
            SEER: '预言家',
            WITCH: '女巫',
            HUNTER: '猎人'
        },
        causeText: {
            killed: '被狼人杀害',
            voted: '被投票淘汰',
            poisoned: '被女巫毒杀',
            shot: '被猎人射杀'
        },
        placeholderNames: ['潘安', '宋玉', '卫玠', '兰陵王', '唐伯虎', '貂蝉', '西施', '王昭君', '杨贵妃'],
        resurrected: '被女巫救活！'
    },
    'en_US': {
        gameTitle: 'Werewolf Battle',
        gameSubtitle: 'Powered by AgentScope',
        waitingToStart: 'Waiting to Start',
        clickStartMessage: 'Click the "Start Game" button to begin a thrilling game of strategy.',
        startGame: 'Start Game',
        gameInProgress: 'In Progress...',
        playAgain: 'Play Again',
        statAlive: 'Alive',
        statWerewolves: 'Werewolves',
        statVillagers: 'Villagers',
        playersList: 'Players',
        gameLog: 'Game Log',
        welcomeMessage: 'Welcome to Werewolf! Ready to start?',
        round: 'Round',
        phaseNight: 'Night',
        phaseDay: 'Day',
        gameStart: 'Game Started!',
        startFailed: 'Start failed: ',
        connectError: 'Connection error: ',
        gameEnd: 'Game Over',
        villagersWin: '🎉 Villagers Win!',
        werewolvesWin: '🐺 Werewolves Win!',
        error: 'Error: ',
        nightPhase: 'Night Phase',
        nightMessage: 'Close your eyes... Werewolves wake up',
        dayPhase: 'Day Phase',
        dayMessage: 'Open your eyes... Start discussion',
        werewolfDiscussion: 'Werewolf Plot',
        speak: 'Speak',
        voteFor: 'votes for',
        roleNames: {
            VILLAGER: 'Villager',
            WEREWOLF: 'Werewolf',
            SEER: 'Seer',
            WITCH: 'Witch',
            HUNTER: 'Hunter'
        },
        causeText: {
            killed: 'killed by werewolves',
            voted: 'eliminated by vote',
            poisoned: 'poisoned by witch',
            shot: 'shot by hunter'
        },
        placeholderNames: ['Pan An', 'Song Yu', 'Wei Jie', 'Prince of Lanling', 'Tang Bohu', 'Diaochan', 'Xi Shi', 'Wang Zhaojun', 'Yang Guifei'],
        resurrected: 'was saved by the witch!'
    }
};

let currentLanguage = localStorage.getItem('werewolf-lang') || 'zh_CN';

function t(key) {
    const keys = key.split('.');
    let value = i18n[currentLanguage];
    if (!value) return key;
    
    for (const k of keys) {
        if (value && typeof value === 'object') {
            value = value[k];
        } else {
            return key;
        }
    }
    return value || key;
}

function setLanguage(lang) {
    currentLanguage = lang;
    localStorage.setItem('werewolf-lang', lang);
    applyTranslations();
    updateLanguageButtons();
    
    // Refresh player display names if game hasn't started
    if (typeof players !== 'undefined' && players.length > 0 && players[0].role === null) {
        const placeholderNames = t('placeholderNames');
        players.forEach((p, i) => {
            if (i < placeholderNames.length) {
                p.name = placeholderNames[i];
            }
        });
        if (typeof renderPlayers === 'function') {
            renderPlayers();
        }
    }
}

function updateLanguageButtons() {
    const zhBtn = document.getElementById('lang-zh_CN');
    const enBtn = document.getElementById('lang-en_US');
    if (zhBtn && enBtn) {
        zhBtn.classList.toggle('active', currentLanguage === 'zh_CN');
        enBtn.classList.toggle('active', currentLanguage === 'en_US');
    }
}

function applyTranslations() {
    document.querySelectorAll('[data-i18n]').forEach(el => {
        const key = el.getAttribute('data-i18n');
        const value = t(key);
        if (value && value !== key) {
            el.textContent = value;
        }
    });
    document.title = t('gameTitle');
}