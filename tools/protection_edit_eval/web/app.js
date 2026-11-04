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
  $("#mask-button").textContent = manual ? "선택한 단서로 진행" : "수정 영역 찾기";
  if (manual) {
    drawCanvas();
    maskStatus.textContent = "모의 분석 단서와 편집할 위치를 준비하세요.";
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
    canvasContext.fillStyle = box.selected ? "rgba(255, 74, 74, .22)" : "rgba(100, 115, 107, .16)";
    canvasContext.fillRect(x, y, width, height);
    canvasContext.strokeStyle = box.selected ? "#ff4a4a" : "#64736b";
    canvasContext.lineWidth = Math.max(2, canvas.width / 700);
    canvasContext.setLineDash([canvas.width / 160, canvas.width / 240]);
    canvasContext.strokeRect(x, y, width, height);
    canvasContext.setLineDash([]);
    canvasContext.fillStyle = box.selected ? "#ff4a4a" : "#64736b";
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
    const selection = document.createElement("label");
    selection.className = "bbox-selection";
    const selected = document.createElement("input");
    selected.type = "checkbox";
    selected.checked = box.selected;
    selected.addEventListener("change", () => { box.selected = selected.checked; drawCanvas(); });
    const selectionText = document.createElement("span");
    selectionText.textContent = "편집할 단서";
    selection.append(selected, selectionText);
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
    const header = document.createElement("div");
    header.className = "bbox-item-header";
    const identity = document.createElement("div");
    identity.className = "bbox-identity";
    identity.append(number, selection);
    header.append(identity, coordinates, remove);
    const fields = document.createElement("div");
    fields.className = "mock-cue-fields";
    const mainDescription = cueField("주요 설명", "예: 동작01 버스 노선", box.mainDescription, 200, true, (value) => { box.mainDescription = value; });
    const subDescription = cueField("부가 설명", "예: 초록색 버스에서 동작01 노선이 확인됩니다", box.subDescription, 500, false, (value) => { box.subDescription = value; });
    const riskLevel = cueSelect("위험도", ["주의", "높음"], box.riskLevel, (value) => { box.riskLevel = value; });
    const riskRationale = cueField("산정 근거", "예: 노선과 차량 외관으로 활동 지역을 추정할 수 있습니다", box.riskRationale, 500, false, (value) => { box.riskRationale = value; }, true);
    fields.append(mainDescription, subDescription, riskLevel, riskRationale);
    item.append(header, fields);
    bboxList.append(item);
  });
  drawCanvas();
}

function cueField(title, placeholder, value, maxLength, required, onChange, multiline = false) {
  const wrapper = document.createElement("label");
  const caption = document.createElement("span");
  caption.textContent = title;
  const control = document.createElement(multiline ? "textarea" : "input");
  if (!multiline) control.type = "text";
  control.value = value || "";
  control.placeholder = placeholder;
  control.maxLength = maxLength;
  control.required = required;
  control.addEventListener("input", () => onChange(control.value));
  wrapper.append(caption, control);
  return wrapper;
}

function cueSelect(title, options, value, onChange) {
  const wrapper = document.createElement("label");
  const caption = document.createElement("span");
  caption.textContent = title;
  const control = document.createElement("select");
  control.required = true;
  control.setAttribute("aria-label", title);
  const placeholder = document.createElement("option");
  placeholder.value = "";
  placeholder.textContent = "위험도를 선택해 주세요";
  control.append(placeholder);
  for (const optionValue of options) {
    const option = document.createElement("option");
    option.value = optionValue;
    option.textContent = optionValue;
    control.append(option);
  }
  control.value = value || "";
  control.addEventListener("change", () => onChange(control.value));
  wrapper.append(caption, control);
  return wrapper;
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
  boxes.push({
    id: `mock-cue-${Date.now()}-${boxes.length + 1}`,
    bbox: [Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1)],
    mainDescription: "", subDescription: "", riskLevel: "", riskRationale: "", selected: true,
  });
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
  heading.textContent = "선택한 단서 편집";
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

  if (session.analysisMode === "mock") {
    const notice = document.createElement("p");
    notice.className = "mock-notice";
    notice.textContent = session.mockNotice || "모의 분석 결과이며 확정된 API 형식이 아닙니다.";
    activeElement.append(notice);
    const cards = document.createElement("section");
    cards.className = "mock-result-cues";
    const cardsTitle = document.createElement("h3");
    cardsTitle.textContent = "분석 단서와 편집 선택";
    cards.append(cardsTitle);
    for (const [index, cue] of (session.bboxRegions || []).entries()) {
      const card = document.createElement("article");
      card.className = `mock-result-cue${cue.selected ? " selected" : ""}`;
      const heading = document.createElement("strong");
      heading.textContent = `${index + 1}. ${cue.mainDescription || cue.target}`;
      card.append(heading);
      const selectState = document.createElement("span");
      selectState.className = "cue-selection-state";
      selectState.textContent = cue.selected ? "이번 편집에 포함" : "이번 편집에서 제외";
      card.append(selectState);
      for (const [label, value] of [["부가 설명", cue.subDescription], ["위험도", cue.riskLevel], ["산정 근거", cue.riskRationale]]) {
        if (!value) continue;
        const row = document.createElement("p");
        row.textContent = `${label}: ${value}`;
        card.append(row);
      }
      cards.append(card);
    }
    activeElement.append(cards);
  }

  const target = document.createElement("p");
  target.className = "session-target";
  target.textContent = `수정 대상:\n${(session.targets || [session.target]).map((item) => `• ${typeof item === "string" ? item : item.target}`).join("\n")}`;
  activeElement.append(target);

  const help = document.createElement("p");
  help.className = "help";
  help.textContent = session.status === "ready"
    ? "선택한 단서의 위치로 mask를 자동 생성했습니다. 아래에서 모델과 편집 옵션을 고르면 바로 편집합니다. mask 미리보기는 테스트 확인용입니다."
    : (session.message || "수정 위치를 찾고 있습니다.");
  activeElement.append(help);

  const pair = document.createElement("div");
  activeElement.append(imageCard("원본", fileUrl(session.id, "source"), "원본 사진을 준비하는 중"));
  const previewDetails = document.createElement("details");
  previewDetails.className = "mask-preview-details";
  const previewSummary = document.createElement("summary");
  previewSummary.textContent = "테스트용 mask 미리보기 보기";
  previewDetails.append(previewSummary);
  const preview = document.createElement("div");
  preview.className = "image-pair with-top-gap";
  preview.append(imageCard("선택 영역 미리보기", session.maskPreviewFile ? fileUrl(session.id, "mask-preview") : "", "mask 생성이 끝나면 표시됩니다."));
  previewDetails.append(preview);
  activeElement.append(previewDetails);

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
      field("추가로 유지할 내용 (선택)", preserve, "preserve-field", "비워두면 선택한 단서를 지우고 주변 장면에 맞게 채웁니다."),
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
      if (boxes.some((box) => !box.mainDescription.trim())) throw new Error("각 단서의 주요 설명을 입력해 주세요.");
      if (boxes.some((box) => !["주의", "높음"].includes(box.riskLevel))) throw new Error("각 단서의 위험도를 선택해 주세요.");
      if (!boxes.some((box) => box.selected)) throw new Error("편집할 단서를 하나 이상 선택해 주세요.");
      const selected = boxes.filter((box) => box.selected);
      formData.set("target", selected.map((box) => [box.mainDescription, box.subDescription].filter(Boolean).join(": ")).join("\n"));
      formData.set("regions", JSON.stringify(boxes.map((box) => ({ ...box, mainDescription: box.mainDescription.trim(), subDescription: box.subDescription.trim(), riskLevel: box.riskLevel.trim(), riskRationale: box.riskRationale.trim() }))));
    }
    const response = await fetch("/api/sessions", { method: "POST", body: formData });
    const result = await response.json();
    if (!response.ok) throw new Error(result.error || "요청을 등록하지 못했습니다.");
    currentSessionId = result.id;
    renderActive(result);
    pollSession(result.id);
    maskStatus.textContent = "선택한 단서의 편집 영역을 준비하고 있습니다.";
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
