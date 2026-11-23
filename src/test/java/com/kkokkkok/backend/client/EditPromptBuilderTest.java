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
        assertFalse(prompt.contains("Objects to remove completely"));
    }

    @Test
    void standaloneObjectClueIsRemovedWholeWhileWritingKeepsItsBoard() {
        String prompt = builder.build(submission(null, 50), List.of(writing("clue-1", "store sign"),
                Map.of("id", "clue-2", "type", "object", "ruleClass", "identifier", "title", "station sign on a post")));

        int writing = prompt.indexOf("Writing to erase:"), objects = prompt.indexOf("Objects to remove completely:");
        assertTrue(writing >= 0 && objects > writing);
        assertTrue(prompt.substring(writing, objects).contains("store sign"));
        assertFalse(prompt.substring(writing, objects).contains("station sign on a post"));
        assertTrue(prompt.substring(objects).contains("station sign on a post"));
        assertTrue(prompt.contains("Remove each of these objects entirely, including its post"));
        assertFalse(prompt.contains("rebuild the scene"));
    }

    @Test
    void objectOnlySelectionHasNoWritingSection() {
        String prompt = builder.build(submission(null, 50), List.of(
                Map.of("id", "clue-2", "type", "object", "ruleClass", "identifier", "title", "station sign on a post")));

        assertFalse(prompt.contains("Writing to erase"));
        assertTrue(prompt.contains("Objects to remove completely"));
    }

    @Test
    void anyStructureClueKeepsTheSceneReconstructionPrompt() {
        String prompt = builder.build(submission(null, 50), List.of(writing("clue-1", "store sign"),
                Map.of("id", "clue-2", "type", "building", "title", "building facade")));

        assertTrue(prompt.contains("rebuild the scene with plausible nearby texture"));
        assertFalse(prompt.contains("Erase only the letters"));
        assertFalse(prompt.contains("Objects to remove completely"));
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
