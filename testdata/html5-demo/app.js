"use strict";

const SAVE_KEY = "ramen-html5-fixture-save-v1";

const scenes = {
    arrival: {
        index: "01",
        speaker: "Рассказчик",
        text: "В небольшой библиотеке за окном медленно гаснут огни города. Ника задержалась у последнего окна.",
        next: "hello"
    },
    hello: {
        index: "02",
        speaker: "Ника",
        text: "Кажется, сегодня здесь стало особенно тихо. Чем заняться перед закрытием?",
        choices: [
            { label: "Открыть старый дневник", next: "diary" },
            { label: "Посмотреть на ночной город", next: "window" }
        ]
    },
    diary: {
        index: "03A",
        speaker: "Ника",
        text: "На первой странице дневника — короткая заметка о том, что даже маленькие истории заслуживают продолжения.",
        next: "diary-ending"
    },
    window: {
        index: "03B",
        speaker: "Ника",
        text: "В окне отражается тёплый свет настольной лампы. Город кажется огромной книгой, в которой ещё много пустых страниц.",
        next: "window-ending"
    },
    "diary-ending": {
        index: "04A",
        speaker: "Рассказчик",
        text: "Ника закрывает дневник и решает сохранить эту мысль на завтра. Конец тестовой ветки.",
        restart: true
    },
    "window-ending": {
        index: "04B",
        speaker: "Рассказчик",
        text: "Ника выключает лампу и выходит из библиотеки. Завтра у этой истории будет новая страница. Конец тестовой ветки.",
        restart: true
    }
};

const speakerElement = document.querySelector("#speaker");
const sceneIndexElement = document.querySelector("#scene-index");
const dialogueElement = document.querySelector("#dialogue");
const choicesElement = document.querySelector("#choices");
const continueButton = document.querySelector("#continue-button");
const restartButton = document.querySelector("#restart-button");
const saveButton = document.querySelector("#save-button");
const loadButton = document.querySelector("#load-button");
const soundButton = document.querySelector("#sound-button");
const statusElement = document.querySelector("#status");

let currentSceneId = "arrival";

function setStatus(message) {
    statusElement.textContent = message;
}

function renderScene() {
    const scene = scenes[currentSceneId];

    if (!scene) {
        currentSceneId = "arrival";
        renderScene();
        return;
    }

    speakerElement.textContent = scene.speaker;
    sceneIndexElement.textContent = `SCENE ${scene.index}`;
    dialogueElement.textContent = scene.text;

    choicesElement.replaceChildren();

    if (Array.isArray(scene.choices) && scene.choices.length > 0) {
        choicesElement.hidden = false;

        for (const choice of scene.choices) {
            const button = document.createElement("button");
            button.type = "button";
            button.className = "choice-button";
            button.textContent = choice.label;
            button.addEventListener("click", () => {
                if (!Object.hasOwn(scenes, choice.next)) {
                    setStatus("Ошибка тестовых данных: неизвестная сцена.");
                    return;
                }

                currentSceneId = choice.next;
                renderScene();
            });
            choicesElement.append(button);
        }
    } else {
        choicesElement.hidden = true;
    }

    continueButton.hidden = Array.isArray(scene.choices) && scene.choices.length > 0;
    continueButton.textContent = scene.restart ? "Пройти ещё раз" : "Продолжить";
}

function advanceScene() {
    const scene = scenes[currentSceneId];

    if (scene.restart) {
        currentSceneId = "arrival";
        renderScene();
        setStatus("История началась сначала.");
        return;
    }

    if (!scene.next || !Object.hasOwn(scenes, scene.next)) {
        setStatus("Следующей сцены нет.");
        return;
    }

    currentSceneId = scene.next;
    renderScene();
}

function saveProgress() {
    try {
        const payload = {
            schema: 1,
            sceneId: currentSceneId,
            savedAt: new Date().toISOString()
        };

        window.localStorage.setItem(SAVE_KEY, JSON.stringify(payload));
        setStatus("Сохранение записано в localStorage.");
    } catch (error) {
        setStatus("Не удалось сохранить данные в localStorage.");
    }
}

function loadProgress() {
    try {
        const raw = window.localStorage.getItem(SAVE_KEY);

        if (!raw) {
            setStatus("Сохранение пока не найдено.");
            return;
        }

        const payload = JSON.parse(raw);

        if (
            payload.schema !== 1 ||
            typeof payload.sceneId !== "string" ||
            !Object.hasOwn(scenes, payload.sceneId)
        ) {
            setStatus("Сохранение имеет неизвестный формат.");
            return;
        }

        currentSceneId = payload.sceneId;
        renderScene();
        setStatus("Сохранение загружено.");
    } catch (error) {
        setStatus("Не удалось прочитать сохранение.");
    }
}

function resetProgress() {
    currentSceneId = "arrival";
    renderScene();
    setStatus("История сброшена. Сохранение не удалено.");
}

function playTestTone() {
    const AudioContextClass = window.AudioContext || window.webkitAudioContext;

    if (!AudioContextClass) {
        setStatus("Web Audio API недоступен в этом браузере.");
        return;
    }

    try {
        const context = new AudioContextClass();
        const oscillator = context.createOscillator();
        const gain = context.createGain();

        oscillator.type = "sine";
        oscillator.frequency.setValueAtTime(660, context.currentTime);
        gain.gain.setValueAtTime(0.12, context.currentTime);
        gain.gain.exponentialRampToValueAtTime(0.001, context.currentTime + 0.18);

        oscillator.connect(gain);
        gain.connect(context.destination);
        oscillator.start();
        oscillator.stop(context.currentTime + 0.18);

        oscillator.addEventListener("ended", () => {
            context.close().catch(() => {});
        }, { once: true });

        setStatus("Тестовый тон отправлен через Web Audio API.");
    } catch (error) {
        setStatus("Не удалось запустить Web Audio API.");
    }
}

continueButton.addEventListener("click", advanceScene);
restartButton.addEventListener("click", resetProgress);
saveButton.addEventListener("click", saveProgress);
loadButton.addEventListener("click", loadProgress);
soundButton.addEventListener("click", playTestTone);

renderScene();
