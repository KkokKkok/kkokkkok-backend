const list = document.querySelector("#results-list");
const status = document.querySelector("#results-status");
const empty = document.querySelector("#empty-results");

function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function imageCard(title, url) {
  const card = element("article", "image-card");
  card.append(element("h3", "", title));
  if (url) {
    const image = document.createElement("img");
    image.src = url;
    image.alt = title;
    image.loading = "lazy";
    card.append(image);
  } else card.append(element("div", "placeholder", "이미지를 사용할 수 없습니다."));
  return card;
}

function fileUrl(session, kind, edit) {
  const query = new URLSearchParams({ kind });
  if (edit) query.set("edit", edit);
  return `/api/sessions/${encodeURIComponent(session.id)}/file?${query}`;
}

function renderEdit(session, edit) {
  const section = element("section", "result-edit");
  const title = element("div", "trial-meta");
  title.append(element("strong", "", edit.model || "편집 실행"));
  const context = Number(edit.cropContextScale) === 0 ? "전체 사진" : `주변 문맥 ${edit.cropContextScale ?? "-"}배`;
  title.append(element("span", "", `${edit.status || "기록"} · 품질 ${edit.quality || "-"} · ${context} · ${edit.elapsedSeconds ?? "-"}초`));
  section.append(title);
  const prompt = element("details", "prompt-details");
  prompt.append(element("summary", "", "실제 전송한 편집 프롬프트 보기"));
  prompt.append(element("pre", "", edit.prompt || "이전 실행 기록에는 프롬프트가 저장되지 않았습니다."));
  section.append(prompt);
  section.append(element("p", "preserve-note", `유지 요청: ${edit.preserve || "없음"}`));
  const images = element("div", "result-images");
  images.append(imageCard("원본", fileUrl(session, "source")));
  images.append(imageCard("수정 mask", fileUrl(session, "mask-preview")));
  images.append(imageCard("편집 결과", edit.status === "completed" ? fileUrl(session, "result", edit.id) : ""));
  section.append(images);
  if (edit.message) section.append(element("p", "status", edit.message));
  return section;
}

function renderSession(session) {
  const card = element("article", "result-session");
  const heading = element("div", "session-title");
  const info = element("div");
  info.append(element("h3", "", session.target || "수정 대상 없음"));
  info.append(element("p", "help", `${session.createdAt || "시간 미상"} · mask ${session.maskElapsedSeconds ?? "-"}초 · 신뢰도 ${session.maskConfidence ?? "-"}`));
  heading.append(info);
  heading.append(element("span", `pill${session.status === "failed" ? " failed" : ""}`, session.status || "기록"));
  card.append(heading);
  if (session.analysisMode === "mock") {
    card.append(element("p", "mock-notice", session.mockNotice || "모의 분석 결과이며 확정된 API 형식이 아닙니다."));
  }
  if (session.bboxRegions?.length) {
    const cueList = element("div", "saved-cue-list");
    session.bboxRegions.forEach((region, index) => {
      const cue = element("article", `mock-result-cue${region.selected ? " selected" : ""}`);
      cue.append(element("strong", "", `${index + 1}. ${region.mainDescription || region.target}`));
      cue.append(element("span", "cue-selection-state", region.selected ? "이번 편집에 포함" : "이번 편집에서 제외"));
      for (const [label, value] of [["부가 설명", region.subDescription], ["위험도", region.riskLevel], ["산정 근거", region.riskRationale]]) {
        if (value) cue.append(element("p", "", `${label}: ${value}`));
      }
      cue.append(element("code", "saved-bbox-list", `bbox [${region.bbox.map((value) => Number(value).toFixed(3)).join(", ")}]`));
      cueList.append(cue);
    });
    card.append(element("p", "help", "사용자 지정 bbox · EXIF 보정 사진 기준 [왼쪽 x, 위쪽 y, 오른쪽 x, 아래쪽 y], 0~1 정규화"), cueList);
  }
  if (session.message && session.status === "failed") card.append(element("p", "status error", session.message));
  if (!session.edits?.length) card.append(element("p", "empty-state", "아직 편집 실행 기록이 없습니다."));
  for (const edit of session.edits || []) card.append(renderEdit(session, edit));
  return card;
}

async function refresh() {
  status.textContent = "기록을 불러오는 중…";
  status.classList.remove("error");
  try {
    const response = await fetch("/api/sessions");
    if (!response.ok) throw new Error(`기록을 불러오지 못했습니다 (${response.status}).`);
    const sessions = await response.json();
    list.replaceChildren(...sessions.map(renderSession));
    empty.classList.toggle("hidden", sessions.length !== 0);
    status.textContent = `${sessions.length}개 세션`;
  } catch (error) {
    status.textContent = error.message || "기록을 불러오지 못했습니다.";
    status.classList.add("error");
  }
}

document.querySelector("#refresh-button").addEventListener("click", refresh);
refresh();
