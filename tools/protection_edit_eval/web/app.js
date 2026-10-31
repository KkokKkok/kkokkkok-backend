const $ = (selector, root = document) => root.querySelector(selector);
const historyElement = $("#history");
const activeElement = $("#active-session");
const maskStatus = $("#mask-status");
let currentSessionId = null;
let modelIds = ["gpt-image-2.5-sunburst", "gpt-image-2.5-flare", "gpt-image-2", "gpt-image-1.5", "gpt-image-1-mini"];

const labels = {
  "gpt-image-2.5-sunburst": "GPT Image 2.5 Sunburst · 정밀 편집",
  "gpt-image-2.5-flare": "GPT Image 2.5 Flare · 빠른 편집",
  "gpt-image-2": "GPT Image 2",
  "gpt-image-1.5": "GPT Image 1.5",
  "gpt-image-1-mini": "GPT Image 1 Mini",
};

function fileUrl(sessionId, kind, editId = "") {
  const query = new URLSearchParams({ kind });
  if (editId) query.set("edit", editId);
  return `/api/sessions/${encodeURIComponent(sessionId)}/file?${query}`;
}

function imageCard(title, src, alt) {
  const card = document.createElement("div");
  card.className = "image-card";
  const heading = document.createElement("h3");
  heading.textContent = title;
  card.append(heading);
  if (src) {
    const image = document.createElement("img");
    image.src = src;
    image.alt = alt;
    image.loading = "lazy";
    card.append(image);
  } else {
    const placeholder = document.createElement("div");
    placeholder.className = "placeholder";
    placeholder.textContent = alt;
    card.append(placeholder);
  }
  return card;
}

function makeSelect(name, options, selected) {
  const select = document.createElement("select");
  select.name = name;
  for (const [value, label] of options) {
    const option = document.createElement("option");
    option.value = value;
    option.textContent = label;
    option.selected = value === selected;
    select.append(option);
  }
  return select;
}

function field(labelText, control, extraClass = "") {
  const wrapper = document.createElement("label");
  wrapper.className = `field ${extraClass}`.trim();
  const label = document.createElement("span");
  label.textContent = labelText;
  wrapper.append(label, control);
  return wrapper;
}

function modelOptions() {
  const order = ["gpt-image-2.5-sunburst", "gpt-image-2.5-flare", "gpt-image-2", "gpt-image-1.5", "gpt-image-1-mini"];
  return order.filter((id) => modelIds.includes(id)).map((id) => [id, labels[id] || id]);
}

function renderActive(session) {
  currentSessionId = session.id;
  activeElement.classList.remove("hidden");
  activeElement.replaceChildren();

  const title = document.createElement("div");
  title.className = "session-title";
  const titleCopy = document.createElement("div");
  const step = document.createElement("p");
  step.className = "step";
  step.textContent = "수정 영역 확인";
  const heading = document.createElement("h2");
  heading.textContent = "mask 미리보기";
  titleCopy.append(step, heading);
  const status = document.createElement("span");
  status.className = `pill${session.status === "failed" ? " failed" : ""}`;
  const maskTime = Number.isFinite(session.maskElapsedSeconds) ? `mask ${session.maskElapsedSeconds}초` : "mask 시간 기록 전";
  status.textContent = session.status === "ready"
    ? `${maskTime} · 신뢰도 ${Math.round((session.maskConfidence || 0) * 100)}%`
    : session.message || session.status;
  title.append(titleCopy, status);
  activeElement.append(title);

  const target = document.createElement("p");
  target.className = "session-target";
  target.textContent = `수정 대상: ${session.target}`;
  activeElement.append(target);

  const help = document.createElement("p");
  help.className = "help";
  help.textContent = session.status === "ready"
    ? `붉은 영역이 편집될 부분입니다. 같은 mask로 편집 모델을 바꿔가며 비교할 수 있어요. mask 모델: ${session.maskModel}`
    : (session.message || "수정 위치를 찾고 있습니다.");
  activeElement.append(help);

  const pair = document.createElement("div");
  pair.className = "image-pair with-top-gap";
  pair.append(
    imageCard("원본", fileUrl(session.id, "source"), "원본 사진을 준비하는 중"),
    imageCard("수정 영역 미리보기", session.maskPreviewFile ? fileUrl(session.id, "mask-preview") : "", "mask 생성이 끝나면 미리보기가 표시됩니다."),
  );
  activeElement.append(pair);

  if (session.status === "failed") {
    const error = document.createElement("p");
    error.className = "status error with-top-gap";
    error.textContent = session.message || "수정 영역을 찾지 못했습니다.";
    activeElement.append(error);
  }

  if (session.status === "ready") {
    const form = document.createElement("form");
    form.className = "edit-form";
    const model = makeSelect("model", modelOptions(), "gpt-image-2.5-sunburst");
    const quality = makeSelect("quality", [], "xhigh");
    const updateQualityOptions = () => {
      const supportsExtra = ["gpt-image-2.5-sunburst", "gpt-image-2.5-flare"].includes(model.value);
      const currentQuality = quality.value;
      const options = [["low", "낮음"], ["medium", "보통"], ["high", "높음"]];
      if (supportsExtra) options.push(["xhigh", "매우 높음"], ["max", "최대"]);
      quality.replaceChildren(...options.map(([value, text]) => {
        const option = document.createElement("option");
        option.value = value;
        option.textContent = text;
        return option;
      }));
      quality.value = options.some(([value]) => value === currentQuality) ? currentQuality : (supportsExtra ? "xhigh" : "high");
    };
    updateQualityOptions();
    model.addEventListener("change", updateQualityOptions);
    const crop = makeSelect("cropContextScale", [["1.5", "1.5배"], ["2", "2배"], ["3", "3배"], ["4", "4배"], ["6", "6배"]], "3");
    const preserve = document.createElement("textarea");
    preserve.name = "preserve";
    preserve.rows = 2;
    preserve.maxLength = 2000;
    preserve.placeholder = "예: 간판 뒤의 초록색 가로 결과 원근, 조명은 유지해줘";
    const submit = document.createElement("button");
    submit.className = "button primary";
    submit.type = "submit";
    submit.textContent = "이 모델로 편집";
    form.append(
      field("이미지 편집 모델", model),
      field("품질", quality),
      field("주변 문맥", crop),
      submit,
      field("유지할 내용 (선택)", preserve, "preserve-field"),
    );
    form.addEventListener("submit", (event) => submitEdit(event, session.id, form));
    activeElement.append(form);
  }

  const trialsTitle = document.createElement("h3");
  trialsTitle.className = "edit-heading";
  trialsTitle.textContent = "이 mask로 실행한 편집 결과";
  activeElement.append(trialsTitle);
  if (!session.edits?.length) {
    const empty = document.createElement("p");
    empty.className = "empty-state";
    empty.textContent = "아직 편집 모델을 실행하지 않았습니다.";
    activeElement.append(empty);
  } else {
    for (const edit of [...session.edits].reverse()) activeElement.append(renderTrial(session, edit));
  }
}

function renderTrial(session, edit) {
  const wrapper = document.createElement("article");
  wrapper.className = "edit-trial";
  const meta = document.createElement("div");
  meta.className = "trial-meta";
  const name = document.createElement("strong");
  name.textContent = labels[edit.model] || edit.model;
  const details = document.createElement("span");
  const elapsed = Number.isFinite(edit.elapsedSeconds) ? ` · ${edit.elapsedSeconds}초` : "";
  details.textContent = `${edit.quality} · 문맥 ${edit.cropContextScale}배${elapsed}`;
  const badge = document.createElement("span");
  badge.className = `pill${edit.status === "failed" ? " failed" : ""}`;
  badge.textContent = edit.status === "completed" ? "완료" : edit.message || edit.status;
  const left = document.createElement("div");
  left.append(name, details);
  meta.append(left, badge);
  wrapper.append(meta);
  const images = document.createElement("div");
  images.className = "edit-results";
  images.append(
    imageCard("원본", fileUrl(session.id, "source"), "원본 사진"),
    imageCard("수정 결과", edit.resultFile ? fileUrl(session.id, "result", edit.id) : "", edit.message || "편집 결과를 기다리는 중"),
  );
  wrapper.append(images);
  if (edit.message && edit.status === "failed") {
    const error = document.createElement("p");
    error.className = "status error with-top-gap-small";
    error.textContent = edit.message;
    wrapper.append(error);
  }
  return wrapper;
}

function renderHistory(sessions) {
  historyElement.replaceChildren();
  $("#empty-history").classList.toggle("hidden", sessions.length > 0);
  for (const session of sessions) {
    const card = document.createElement("article");
    card.className = "session-card";
    const title = document.createElement("div");
    title.className = "session-title";
    const copy = document.createElement("div");
    const heading = document.createElement("h3");
    heading.textContent = session.target;
    const date = document.createElement("p");
    date.className = "help";
    date.textContent = new Date(session.createdAt).toLocaleString();
    copy.append(heading, date);
    const badge = document.createElement("span");
    badge.className = `pill${session.status === "failed" ? " failed" : ""}`;
    const maskTime = Number.isFinite(session.maskElapsedSeconds) ? `mask ${session.maskElapsedSeconds}초` : "mask 대기 중";
    badge.textContent = `${session.status === "ready" ? "준비 완료" : session.status} · ${maskTime} · ${session.edits?.length || 0}회 편집`;
    title.append(copy, badge);
    card.append(title);
    const select = document.createElement("button");
    select.type = "button";
    select.className = "button secondary history-select";
    select.textContent = "이 mask 열기 / 모델 비교";
    select.disabled = session.status !== "ready";
    select.addEventListener("click", async () => {
      const full = await getSession(session.id);
      renderActive(full);
      activeElement.scrollIntoView({ behavior: "smooth", block: "start" });
    });
    card.append(select);
    if (session.edits?.length) {
      const summary = document.createElement("div");
      summary.className = "edit-results history-results";
      for (const edit of session.edits.filter((item) => item.status === "completed")) {
        summary.append(imageCard(labels[edit.model] || edit.model, fileUrl(session.id, "result", edit.id), "수정 결과"));
      }
      if (summary.childElementCount) card.append(summary);
    }
    historyElement.append(card);
  }
}

async function getSession(sessionId) {
  const response = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}`);
  if (!response.ok) throw new Error("실행 기록을 불러오지 못했습니다.");
  return response.json();
}

async function refreshHistory() {
  const response = await fetch("/api/sessions");
  if (!response.ok) throw new Error("실행 기록을 불러오지 못했습니다.");
  renderHistory(await response.json());
}

async function pollSession(sessionId) {
  for (let attempt = 0; attempt < 240; attempt += 1) {
    await new Promise((resolve) => setTimeout(resolve, 2000));
    try {
      const session = await getSession(sessionId);
      if (currentSessionId === sessionId) renderActive(session);
      await refreshHistory();
      const pending = session.status === "queued" || session.status === "masking" || (session.edits || []).some((edit) => edit.status === "queued" || edit.status === "editing");
      if (!pending) return;
    } catch (error) {
      console.error(error);
      return;
    }
  }
}

async function submitEdit(event, sessionId, form) {
  event.preventDefault();
  const button = $("button[type=submit]", form);
  const status = document.createElement("p");
  status.className = "status";
  status.textContent = "편집 요청을 등록했습니다.";
  form.after(status);
  button.disabled = true;
  try {
    const formData = new FormData(form);
    const response = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/edits`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        model: formData.get("model"),
        quality: formData.get("quality"),
        cropContextScale: formData.get("cropContextScale"),
        preserve: formData.get("preserve"),
      }),
    });
    const result = await response.json();
    if (!response.ok) throw new Error(result.error || "편집 요청을 등록하지 못했습니다.");
    const session = await getSession(sessionId);
    renderActive(session);
    pollSession(sessionId);
  } catch (error) {
    status.classList.add("error");
    status.textContent = error.message;
    button.disabled = false;
  }
}

$("#mask-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  maskStatus.classList.remove("error");
  maskStatus.textContent = "수정 위치 찾기를 등록했습니다.";
  const button = $("#mask-button");
  button.disabled = true;
  try {
    const response = await fetch("/api/sessions", { method: "POST", body: new FormData(event.currentTarget) });
    const result = await response.json();
    if (!response.ok) throw new Error(result.error || "요청을 등록하지 못했습니다.");
    currentSessionId = result.id;
    renderActive(result);
    await refreshHistory();
    pollSession(result.id);
    maskStatus.textContent = "진행 중입니다. mask가 준비되면 미리보기가 표시됩니다.";
  } catch (error) {
    maskStatus.classList.add("error");
    maskStatus.textContent = error.message;
  } finally {
    button.disabled = false;
  }
});

$("#refresh-button").addEventListener("click", () => refreshHistory().catch((error) => { maskStatus.textContent = error.message; }));

async function init() {
  try {
    const response = await fetch("/api/models");
    const data = await response.json();
    modelIds = data.imageModels;
    await refreshHistory();
  } catch (error) {
    maskStatus.classList.add("error");
    maskStatus.textContent = error.message;
  }
}

init();
