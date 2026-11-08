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

    private EditSubmission submission(String preservePrompt, int strength) {
        return new EditSubmission(null, new byte[0], "image/jpeg", List.of(), preservePrompt, strength);
    }

    private Map<String, Object> cue(String id, String title) {
        return Map.of("id", id, "title", title, "description", "location identifying detail");
    }
}
