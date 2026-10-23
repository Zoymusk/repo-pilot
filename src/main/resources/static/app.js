"use strict";

const indexForm = document.querySelector("#index-form");
const askForm = document.querySelector("#ask-form");
const indexButton = document.querySelector("#index-button");
const askButton = document.querySelector("#ask-button");
const indexFeedback = document.querySelector("#index-feedback");
const askFeedback = document.querySelector("#ask-feedback");
const answerPanel = document.querySelector("#answer");

function setLoading(button, loading) {
  button.disabled = loading;
  button.classList.toggle("is-loading", loading);
  button.setAttribute("aria-busy", String(loading));
}

function showFeedback(element, message, isError = false) {
  element.textContent = message;
  element.classList.toggle("error", isError);
  element.hidden = false;
}

async function postJson(path, payload) {
  let response;
  try {
    response = await fetch(path, {
      method: "POST",
      headers: { "Content-Type": "application/json", "Accept": "application/json" },
      body: JSON.stringify(payload)
    });
  } catch (error) {
    throw new Error("Could not reach Repo Pilot. Check your connection and try again.");
  }

  const responseText = await response.text();
  let data;
  try {
    data = responseText ? JSON.parse(responseText) : null;
  } catch (error) {
    data = null;
  }

  if (!response.ok) {
    const message = data && (data.message || data.detail || data.title);
    throw new Error(message || `Request failed (${response.status}). Please try again.`);
  }
  if (!data || typeof data !== "object") {
    throw new Error("Repo Pilot returned an unexpected response. Please try again.");
  }
  return data;
}

function addTextElement(parent, tagName, className, text) {
  const element = document.createElement(tagName);
  if (className) element.className = className;
  element.textContent = text;
  parent.append(element);
  return element;
}

function renderCitations(citations) {
  const list = document.querySelector("#citations");
  list.replaceChildren();
  const items = Array.isArray(citations) ? citations : [];
  document.querySelector("#citation-count").textContent = String(items.length);
  document.querySelector("#no-citations").hidden = items.length !== 0;

  items.forEach((citation) => {
    const item = document.createElement("li");
    item.className = "citation-item";
    addTextElement(item, "span", "file-icon", "{}");
    addTextElement(item, "span", "citation-path", citation.filePath || "Unknown file");
    addTextElement(item, "span", "citation-lines", `L${citation.startLine ?? "?"}–L${citation.endLine ?? "?"}`);
    list.append(item);
  });
}

function renderPlan(plan) {
  const list = document.querySelector("#plan-steps");
  list.replaceChildren();
  const steps = plan && Array.isArray(plan.steps) ? plan.steps : [];
  document.querySelector("#step-count").textContent = String(steps.length);
  document.querySelector("#no-steps").hidden = steps.length !== 0;

  steps.forEach((step) => {
    const item = document.createElement("li");
    item.className = "plan-step";
    addTextElement(item, "p", "step-description", step.description || "No description provided.");

    const meta = document.createElement("div");
    meta.className = "plan-meta";

    const files = Array.isArray(step.filesToChange) ? step.filesToChange : [];
    if (files.length) {
      const filesGroup = document.createElement("div");
      addTextElement(filesGroup, "div", "meta-label", "Files to change");
      const tags = document.createElement("div");
      tags.className = "tag-list";
      files.forEach((file) => addTextElement(tags, "span", "tag", file));
      filesGroup.append(tags);
      meta.append(filesGroup);
    }

    const tests = Array.isArray(step.testsToAdd) ? step.testsToAdd : [];
    if (tests.length) {
      const testsGroup = document.createElement("div");
      addTextElement(testsGroup, "div", "meta-label", "Tests to add");
      tests.forEach((test) => {
        const testEntry = document.createElement("div");
        addTextElement(testEntry, "span", "tag test-tag", test.testClass || "Test");
        if (test.checks) addTextElement(testEntry, "p", "test-checks", test.checks);
        testsGroup.append(testEntry);
      });
      meta.append(testsGroup);
    }

    if (meta.childElementCount) item.append(meta);
    list.append(item);
  });
}

indexForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  indexFeedback.hidden = true;
  setLoading(indexButton, true);
  try {
    const repositoryUrl = new FormData(indexForm).get("repositoryUrl").trim();
    const result = await postJson("/api/repositories/index", { repositoryUrl });
    if (!Number.isInteger(result.indexedChunkCount)) {
      throw new Error("Repo Pilot returned an invalid index count. Please try again.");
    }
    showFeedback(indexFeedback, `Repository indexed successfully — ${result.indexedChunkCount.toLocaleString()} ${result.indexedChunkCount === 1 ? "chunk" : "chunks"} ready to explore.`);
  } catch (error) {
    showFeedback(indexFeedback, error.message || "Unable to index the repository. Please try again.", true);
  } finally {
    setLoading(indexButton, false);
  }
});

askForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  askFeedback.hidden = true;
  setLoading(askButton, true);
  try {
    const question = new FormData(askForm).get("question").trim();
    const result = await postJson("/api/repositories/ask", { question });
    if (typeof result.explanation !== "string" || !result.prPlan) {
      throw new Error("Repo Pilot returned an incomplete answer. Please try again.");
    }
    document.querySelector("#explanation").textContent = result.explanation;
    renderCitations(result.citations);
    renderPlan(result.prPlan);
    answerPanel.hidden = false;
    answerPanel.scrollIntoView({ behavior: "smooth", block: "start" });
  } catch (error) {
    showFeedback(askFeedback, error.message || "Unable to answer this question. Please try again.", true);
  } finally {
    setLoading(askButton, false);
  }
});
