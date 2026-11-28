package com.kkokkkok.backend.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kkokkkok.backend.domain.EditSubmission;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EditPromptBuilderTest {
    private final EditPromptBuilder builder = new EditPromptBuilder();

    @Test
    void defaultPromptExplainsNaturalReconstructionAndPreservation() {
        String prompt = builder.build(submission(null, 50), List.of(cue("sign", "store sign")));

        assertTrue(prompt.contains("remove the identifying detail completely"));
        assertTrue(prompt.contains("texture, patterns, perspective, lighting, focus, and image grain"));
        assertTrue(prompt.contains("Do not leave recognizable fragments, blur, mosaic, a blank patch"));
        assertTrue(prompt.contains("Keep all unmarked content and unrelated details unchanged"));
        assertTrue(prompt.contains("overall mood and style"));
    }

    @Test
    void promptCombinesSelectedCluesAndHonorsOptionalPreservationRequest() {
        String prompt = builder.build(submission("Keep the warm cafe lighting", 75), List.of(
                cue("logo", "round cafe logo"), cue("sign", "large storefront lettering")));

        assertTrue(prompt.contains("Remove or naturally replace all selected clues together in one coordinated edit"));
        assertTrue(prompt.contains("round cafe logo"));
        assertTrue(prompt.contains("large storefront lettering"));
        assertTrue(prompt.contains("User's preservation request: Keep the warm cafe lighting"));
        assertTrue(prompt.contains("without expanding the edit beyond the marked areas"));
    }

    @Test
    void blankPreservationRequestIsOmittedAndLowStrengthStaysSubtle() {
        String prompt = builder.build(submission("  ", 20), List.of(cue("id", "street sign")));

        assertTrue(prompt.contains("Use a subtle edit, changing only what is necessary"));
        assertFalse(prompt.contains("User's preservation request"));
    }

    @Test
    void writingOnlySelectionErasesTheMarksAndKeepsBoardsAndStructures() {
        String prompt = builder.build(submission("Keep the warm cafe lighting", 50), List.of(
                writing("clue-1", "store sign"), writing("clue-2", "phone number")));

        assertTrue(prompt.contains("Erase only the letters, numbers, and logos of this writing"));
        assertTrue(prompt.contains("Keep the sign board, frame, and edges the writing is on"));
        assertTrue(prompt.contains("store sign") && prompt.contains("phone number"));
        assertTrue(prompt.contains("User's preservation request: Keep the warm cafe lighting"));
        assertFalse(prompt.contains("rebuild the scene"));
        assertFalse(prompt.contains("Objects to remove"));
    }

    @Test
    void standaloneObjectClueIsRemovedWholeWhileWritingKeepsItsBoard() {
        String prompt = builder.build(submission(null, 50), List.of(writing("clue-1", "store sign"),
                Map.of("id", "clue-2", "type", "object", "ruleClass", "identifier", "title", "station sign on a post")));

        int writing = prompt.indexOf("Writing to erase:"), objects = prompt.indexOf("Objects to remove:");
        assertTrue(writing >= 0 && objects > writing);
        assertTrue(prompt.substring(writing, objects).contains("store sign"));
        assertFalse(prompt.substring(writing, objects).contains("station sign on a post"));
        assertTrue(prompt.substring(objects).contains("station sign on a post"));
        assertTrue(prompt.contains("Remove each of these objects entirely, including its post"));
        assertFalse(prompt.contains("rebuild the scene"));
    }

    @Test
    void objectClueSpanningABuildingIsNotRemovedWhole() {
        // A location guess drawn on the building it is based on: 0.85 x 0.49 of the photo.
        String prompt = builder.build(submission(null, 50), List.of(Map.of("id", "clue-9", "type", "object",
                "ruleClass", "other", "title", "location guess from the building", "bbox", List.of(0.0, 0.0, 0.85, 0.49))));

        assertFalse(prompt.contains("Objects to remove"));
        assertFalse(prompt.contains("remove it entirely"));
        assertTrue(prompt.contains("rebuild the scene"));
        // A small object (a sign on its post) is still removed whole.
        String small = builder.build(submission(null, 50), List.of(Map.of("id", "clue-2", "type", "object",
                "title", "station sign on a post", "bbox", List.of(0.4, 0.3, 0.6, 0.8))));
        assertTrue(small.contains("Objects to remove"));
    }

    @Test
    void locationGuessIsNeverRemovedWholeButARealClueInAGuessCategoryIs() {
        // A guess drawn on a landmark (small box) gets the scene edit, not removal; a station name filed under the
        // same category keeps its identifier class and is removed as an object.
        String guess = builder.build(submission(null, 50), List.of(Map.of("id", "clue-8", "type", "object",
                "ruleClass", "other", "category", "추정 위치", "title", "guess from other clues", "bbox", List.of(0.52, 0.09, 0.57, 0.31))));
        assertFalse(guess.contains("Objects to remove"));
        String station = builder.build(submission(null, 50), List.of(Map.of("id", "clue-2", "type", "object",
                "ruleClass", "identifier", "category", "추정 위치", "title", "station name", "bbox", List.of(0.1, 0.5, 0.2, 0.9))));
        assertTrue(station.contains("Objects to remove"));
    }

    @Test
    void logosMascotsAndLetteringKeepTheirShapeWhileOtherObjectsAreRemoved() {
        String prompt = builder.build(submission(null, 50), List.of(writing("clue-1", "store sign"),
                Map.of("id", "clue-2", "type", "object", "ruleClass", "identifier", "category", "마스코트 캐릭터", "title", "school mascot"),
                Map.of("id", "clue-3", "type", "object", "ruleClass", "chain", "category", "로고 디자인", "title", "lit logo"),
                Map.of("id", "clue-4", "type", "object", "ruleClass", "identifier", "category", "지하철 안내 기둥", "title", "station pillar")));
        int marks = prompt.indexOf("Logos and marks to erase:"), objects = prompt.indexOf("Objects to remove:");
        assertTrue(marks >= 0 && objects > marks);
        assertTrue(prompt.substring(marks, objects).contains("school mascot"));
        assertTrue(prompt.substring(marks, objects).contains("lit logo"));
        assertTrue(prompt.substring(objects).contains("station pillar"));
        assertTrue(prompt.contains("A mascot or costume keeps its body"));
        assertTrue(prompt.contains("never a hole, glow, halo, or blur"));
        assertTrue(prompt.contains("also remove their shadows, outlines, mounting rails"));
    }

    @Test
    void objectOnlySelectionHasNoWritingSection() {
        String prompt = builder.build(submission(null, 50), List.of(
                Map.of("id", "clue-2", "type", "object", "ruleClass", "identifier", "title", "station sign on a post")));

        assertFalse(prompt.contains("Writing to erase"));
        assertTrue(prompt.contains("Objects to remove"));
    }

    @Test
    void anyStructureClueKeepsTheSceneReconstructionPrompt() {
        String prompt = builder.build(submission(null, 50), List.of(writing("clue-1", "store sign"),
                Map.of("id", "clue-2", "type", "building", "title", "building facade")));

        assertTrue(prompt.contains("rebuild the scene with plausible nearby texture"));
        assertFalse(prompt.contains("Erase only the letters"));
        assertFalse(prompt.contains("Objects to remove"));
    }

    private EditSubmission submission(String preservePrompt, int strength) {
        return new EditSubmission(null, new byte[0], "image/jpeg", List.of(), preservePrompt, strength);
    }

    private Map<String, Object> cue(String id, String title) {
        return Map.of("id", id, "title", title, "description", "location identifying detail", "ruleClass", "structure");
    }

    private Map<String, Object> writing(String id, String title) {
        return Map.of("id", id, "type", "text", "title", title, "description", "readable store name", "ruleClass", "identifier");
    }
}
