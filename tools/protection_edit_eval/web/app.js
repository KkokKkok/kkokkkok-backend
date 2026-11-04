const $ = (selector, root = document) => root.querySelector(selector);
const activeElement = $("#active-session");
const maskStatus = $("#mask-status");
const imageInput = $('#mask-form input[name="image"]');
const selectedImagePreview = $("#selected-image-preview");
const manualPanel = $("#manual-bbox-panel");
const targetField = $("#target-field");
const canvas = $("#bbox-canvas");
const canvasContext = canvas.getContext("2d");
const canvasPlaceholder = $("#canvas-placeholder");
const bboxList = $("#bbox-list");
const bboxCount = $("#bbox-count");
let currentSessionId = null;
let modelIds = ["gpt-image-2.5-sunburst", "gpt-image-2.5-flare", "gpt-image-2", "gpt-image-1.5", "gpt-image-1-mini"];

const labels = {
  "gpt-image-2.5-sunburst": "GPT Image 2.5 Sunburst · 정밀 편집",
  "gpt-image-2.5-flare": "GPT Image 2.5 Flare · 빠른 편집",
  "gpt-image-2": "GPT Image 2",
  "gpt-image-1.5": "GPT Image 1.5",
  "gpt-image-1-mini": "GPT Image 1 Mini",
};

let selectedImageUrl = null;
let sourceImage = null;
let boxes = [];
let drawingStart = null;

function currentMode() {
  return $('#mask-form input[name="mode"]:checked').value;
}

function updateMode() {
  const manual = currentMode() === "manual-bbox";
  manualPanel.classList.toggle("hidden", !manual);
  targetField.classList.toggle("hidden", manual);
  const target = $('textarea[name="target"]');
  target.required = !manual;
  selectedImagePreview.classList.toggle("hidden", !selectedImageUrl || manual);
  $("#mask-button").textContent = manual ? "bbox mask 만들기" : "수정 영역 찾기";
  if (manual) {
    drawCanvas();
    maskStatus.textContent = "사진 위에서 드래그해 수정 영역을 지정하세요.";
  } else {
    maskStatus.textContent = "";
  }
}

function drawCanvas() {
  if (!sourceImage || !canvas.width || !canvas.height) return;
  canvasContext.clearRect(0, 0, canvas.width, canvas.height);
  canvasContext.drawImage(sourceImage, 0, 0, canvas.width, canvas.height);
  boxes.forEach((box, index) => {
    const [left, top, right, bottom] = box.bbox;
    const x = left * canvas.width;
    const y = top * canvas.height;
    const width = (right - left) * canvas.width;
    const height = (bottom - top) * canvas.height;
    canvasContext.fillStyle = "rgba(255, 74, 74, .22)";
    canvasContext.fillRect(x, y, width, height);
    canvasContext.strokeStyle = "#ff4a4a";
    canvasContext.lineWidth = Math.max(2, canvas.width / 700);
    canvasContext.setLineDash([canvas.width / 160, canvas.width / 240]);
    canvasContext.strokeRect(x, y, width, height);
    canvasContext.setLineDash([]);
    canvasContext.fillStyle = "#ff4a4a";
    canvasContext.font = `bold ${Math.max(14, canvas.width / 45)}px sans-serif`;
    canvasContext.fillText(String(index + 1), x + 6, y + Math.max(18, canvas.width / 40));
  });
  if (drawingStart) {
    const [left, top] = drawingStart;
    const [right, bottom] = drawingPointer || drawingStart;
    const x = Math.min(left, right) * canvas.width;
    const y = Math.min(top, bottom) * canvas.height;
    canvasContext.fillStyle = "rgba(255, 74, 74, .18)";
    canvasContext.fillRect(x, y, Math.abs(right - left) * canvas.width, Math.abs(bottom - top) * canvas.height);
    canvasContext.strokeStyle = "#ff4a4a";
    canvasContext.lineWidth = Math.max(2, canvas.width / 700);
    canvasContext.setLineDash([canvas.width / 160, canvas.width / 240]);
    canvasContext.strokeRect(x, y, Math.abs(right - left) * canvas.width, Math.abs(bottom - top) * canvas.height);
    canvasContext.setLineDash([]);
  }
}

let drawingPointer = null;

function normalizedPoint(event) {
  const rect = canvas.getBoundingClientRect();
  return [
    Math.max(0, Math.min(1, (event.clientX - rect.left) / rect.width)),
    Math.max(0, Math.min(1, (event.clientY - rect.top) / rect.height)),
  ];
}

function renderBoxes() {
  bboxCount.textContent = String(boxes.length);
  bboxList.replaceChildren();
  boxes.forEach((box, index) => {
    const item = document.createElement("li");
    item.className = "bbox-item";
    const number = document.createElement("span");
    number.className = "bbox-number";
    number.textContent = String(index + 1);
    const label = document.createElement("input");
    label.type = "text";
    label.maxLength = 200;
    label.required = true;
    label.value = box.target;
    label.placeholder = "예: 컵 앞면 로고";
    label.setAttribute("aria-label", `수정 영역 ${index + 1} 설명`);
    label.addEventListener("input", () => { box.target = label.value; });
    const coordinates = document.createElement("code");
    coordinates.textContent = box.bbox.map((value) => value.toFixed(3)).join(", ");
    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "button secondary bbox-remove";
    remove.textContent = "삭제";
    remove.setAttribute("aria-label", `수정 영역 ${index + 1} 삭제`);
    remove.addEventListener("click", () => {
      boxes.splice(index, 1);
      renderBoxes();
    });
    item.append(number, label, coordinates, remove);
    bboxList.append(item);
  });
  drawCanvas();
}

imageInput.addEventListener("change", () => {
  if (selectedImageUrl) URL.revokeObjectURL(selectedImageUrl);
  const file = imageInput.files?.[0];
  if (!file) {
    selectedImagePreview.removeAttribute("src");
    selectedImagePreview.classList.add("hidden");
    selectedImageUrl = null;
    sourceImage = null;
    canvas.width = 0;
    canvas.height = 0;
    canvas.classList.remove("ready");
    canvasPlaceholder.classList.remove("hidden");
    boxes = [];
    renderBoxes();
    return;
  }
  selectedImageUrl = URL.createObjectURL(file);
  selectedImagePreview.src = selectedImageUrl;
  selectedImagePreview.classList.toggle("hidden", currentMode() === "manual-bbox");
  sourceImage = new Image();
  sourceImage.onload = () => {
    const scale = Math.min(1, 2048 / Math.max(sourceImage.naturalWidth, sourceImage.naturalHeight));
    canvas.width = Math.round(sourceImage.naturalWidth * scale);
    canvas.height = Math.round(sourceImage.naturalHeight * scale);
    canvas.classList.add("ready");
    canvasPlaceholder.classList.add("hidden");
    drawCanvas();
  };
  sourceImage.onerror = () => {
    sourceImage = null;
    canvas.classList.remove("ready");
    canvasPlaceholder.textContent = "사진 미리보기를 불러오지 못했습니다.";
    canvasPlaceholder.classList.remove("hidden");
  };
  sourceImage.src = selectedImageUrl;
  boxes = [];
  renderBoxes();
});

document.querySelectorAll('#mask-form input[name="mode"]').forEach((radio) => radio.addEventListener("change", updateMode));

canvas.addEventListener("pointerdown", (event) => {
  if (currentMode() !== "manual-bbox" || !sourceImage) return;
  if (event.pointerType === "mouse" && event.button !== 0) return;
  if (boxes.length >= 20) {
    maskStatus.textContent = "한 번에 지정할 수 있는 수정 영역은 최대 20개예요.";
    return;
  }
  event.preventDefault();
  canvas.setPointerCapture(event.pointerId);
  drawingStart = normalizedPoint(event);
  drawingPointer = drawingStart;
  drawCanvas();
});
canvas.addEventListener("pointermove", (event) => {
  if (!drawingStart) return;
  drawingPointer = normalizedPoint(event);
  drawCanvas();
});
canvas.addEventListener("pointerup", (event) => {
  if (!drawingStart) return;
  drawingPointer = normalizedPoint(event);
  const [x0, y0] = drawingStart;
  const [x1, y1] = drawingPointer;
  drawingStart = null;
  drawingPointer = null;
  if (Math.abs(x1 - x0) < 0.003 || Math.abs(y1 - y0) < 0.003) {
    drawCanvas();
    maskStatus.textContent = "상자를 만들려면 조금 더 크게 드래그해 주세요.";
    return;
  }
  boxes.push({ bbox: [Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1)], target: "" });
  renderBoxes();
  maskStatus.textContent = "각 영역에 수정할 대상을 적어 주세요.";
});
canvas.addEventListener("pointercancel", () => {
  drawingStart = null;
  drawingPointer = null;
  drawCanvas();
});
$("#clear-bboxes").addEventListener("click", () => {
  boxes = [];
  renderBoxes();
});

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

function field(labelText, control, extraClass = "", hintText = "") {
  const wrapper = document.createElement("label");
  wrapper.className = `field ${extraClass}`.trim();
  const label = document.createElement("span");
  label.textContent = labelText;
  wrapper.append(label, control);
  if (hintText) {
    const hint = document.createElement("small");
    hint.textContent = hintText;
    wrapper.append(hint);
  }
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
    ? session.maskConfidence == null
      ? `${maskTime} · 사용자 지정 bbox`
      : `${maskTime} · 신뢰도 ${Math.round(session.maskConfidence * 100)}%`
    : session.message || session.status;
  title.append(titleCopy, status);
  activeElement.append(title);

  const target = document.createElement("p");
  target.className = "session-target";
  target.textContent = `수정 대상:\n${(session.targets || [session.target]).map((item) => `• ${typeof item === "string" ? item : item.target}`).join("\n")}`;
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
    const crop = makeSelect("cropContextScale", [["0", "전체 사진 · 백엔드 기본"], ["1.5", "1.5배"], ["2", "2배"], ["3", "3배"], ["4", "4배"], ["6", "6배"]], "0");
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
      field("품질", quality, "", "수정 결과를 얼마나 높은 화질로 만들지 선택해요. 높이면 시간과 비용이 늘 수 있어요."),
      field("주변 문맥", crop, "", "전체 사진이 백엔드 기본 방식입니다. 배율을 고르면 수정할 곳 주변 일부만 잘라 비교합니다."),
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
  const context = Number(edit.cropContextScale) === 0 ? "전체 사진" : `문맥 ${edit.cropContextScale}배`;
  details.textContent = `${edit.quality} · ${context}${elapsed}`;
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

async function getSession(sessionId) {
  const response = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}`);
  if (!response.ok) throw new Error("실행 기록을 불러오지 못했습니다.");
  return response.json();
}

async function pollSession(sessionId) {
  for (let attempt = 0; attempt < 240; attempt += 1) {
    await new Promise((resolve) => setTimeout(resolve, 2000));
    try {
      const session = await getSession(sessionId);
      if (currentSessionId === sessionId) renderActive(session);
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
    const formData = new FormData(event.currentTarget);
    if (currentMode() === "manual-bbox") {
      if (!boxes.length) throw new Error("사진 위에서 수정할 영역을 한 개 이상 드래그해 주세요.");
      if (boxes.some((box) => !box.target.trim())) throw new Error("각 영역에 수정할 대상을 적어 주세요.");
      formData.set("target", boxes.map((box) => box.target.trim()).join("\n"));
      formData.set("regions", JSON.stringify(boxes.map((box) => ({ target: box.target.trim(), bbox: box.bbox }))));
    }
    const response = await fetch("/api/sessions", { method: "POST", body: formData });
    const result = await response.json();
    if (!response.ok) throw new Error(result.error || "요청을 등록하지 못했습니다.");
    currentSessionId = result.id;
    renderActive(result);
    pollSession(result.id);
    maskStatus.textContent = "진행 중입니다. mask가 준비되면 미리보기가 표시됩니다.";
  } catch (error) {
    maskStatus.classList.add("error");
    maskStatus.textContent = error.message;
  } finally {
    button.disabled = false;
  }
});

async function init() {
  try {
    const response = await fetch("/api/models");
    const data = await response.json();
    modelIds = data.imageModels;
  } catch (error) {
    maskStatus.classList.add("error");
    maskStatus.textContent = error.message;
  }
}

init();
updateMode();
