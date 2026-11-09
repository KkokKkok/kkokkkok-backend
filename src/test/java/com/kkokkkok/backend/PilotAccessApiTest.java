package com.kkokkkok.backend;

import com.kkokkkok.backend.domain.*;
import com.kkokkkok.backend.repository.*;
import com.kkokkkok.backend.service.PilotSessionService;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"app.pilot.enabled=true", "analysis.provider=stub", "editing.provider=stub"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class PilotAccessApiTest {
    @Autowired MockMvc mvc;
    @Autowired PilotInviteRepository invites;
    @Autowired PilotInstallationRepository installations;
    @Autowired ImageAssetRepository assets;
    private int address;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:h2:mem:pilot-access;DB_CLOSE_DELAY=-1");
        r.add("assets.storage-root", () -> Path.of("build/pilot-test-assets").toAbsolutePath().toString());
    }
    @Test void twoInstallationsCannotReadChangeAnalyzeOrEditEachOthersData() throws Exception {
        String a = enroll("AAAAAAAAAAAAAAAAAAAA", 2), b = enroll("BBBBBBBBBBBBBBBBBBBB", 1);
        String asset = upload(a), other = upload(b);
        String inspection = field(call(post("/api/inspections").header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content("{\"assetId\":\""+asset+"\"}"), a, 202), "inspectionId");
        var completed = call(get("/api/inspections/"+inspection), a, 200);
        String cue = com.jayway.jsonpath.JsonPath.read(completed.getResponse().getContentAsString(), "$.riskFactors[0].id");
        String editBody = "{\"inspectionId\":\""+inspection+"\",\"cueIds\":[\""+cue+"\"]}";
        UUID editKey = UUID.randomUUID();
        var edited = call(post("/api/edits").header("Idempotency-Key", editKey).contentType(MediaType.APPLICATION_JSON).content(editBody), a, 201);
        String editedAsset = field(edited, "editedAssetId");
        call(get("/api/assets/"+editedAsset), b, 404);
        call(get("/api/edits/"+editKey), b, 404);
        call(post("/api/edits").header("Idempotency-Key", editKey).contentType(MediaType.APPLICATION_JSON).content(editBody), b, 404);
        call(post("/api/edits").header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(editBody), b, 404);
        for (String suffix : List.of("", "/content")) call(get("/api/assets/"+asset+suffix), b, 404);
        call(put("/api/assets/"+asset+"/labels").contentType(MediaType.APPLICATION_JSON).content("{\"labels\":[\"합성\"]}"), b, 404);
        call(put("/api/assets/"+asset+"/capture-location").contentType(MediaType.APPLICATION_JSON).content("{\"latitude\":37.5,\"longitude\":127,\"source\":\"manual\"}"), b, 404);
        call(delete("/api/assets/"+asset), b, 404);
        call(get("/api/inspections/"+inspection), b, 404);
        call(get("/api/inspections").param("assetId",asset), b, 404);
        call(post("/api/inspections").header("Idempotency-Key",UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content("{\"assetId\":\""+asset+"\"}"), b, 404);
        UUID batch = UUID.randomUUID();
        String batchBody = "{\"items\":[{\"assetId\":\""+asset+"\"}]}";
        call(post("/api/inspection-batches").header("Idempotency-Key", batch).contentType(MediaType.APPLICATION_JSON).content(batchBody), a, 202);
        call(get("/api/inspection-batches/"+batch), b, 404);
        call(post("/api/inspection-batches").header("Idempotency-Key",UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(batchBody), b, 404);
        String placeBody="{\"name\":\"합성 장소\",\"category\":\"home\",\"latitude\":37.5,\"longitude\":127,\"radiusMeters\":300}";
        String place=field(call(post("/api/places").contentType(MediaType.APPLICATION_JSON).content(placeBody), a, 201),"placeId");
        String bPlace=field(call(post("/api/places").contentType(MediaType.APPLICATION_JSON).content(placeBody), b, 201),"placeId");
        call(post("/api/places/"+bPlace+"/assets").contentType(MediaType.APPLICATION_JSON).content("{\"assetId\":\""+asset+"\"}"), b, 404);
        call(post("/api/places/"+place+"/assets").contentType(MediaType.APPLICATION_JSON).content("{\"assetId\":\""+asset+"\"}"), a, 200);
        for(String path:List.of("/api/places/"+place,"/api/places/"+place+"/assets","/api/cumulative-runs/place/"+place)) call(get(path),b,404);
        call(put("/api/places/"+place).contentType(MediaType.APPLICATION_JSON).content(placeBody),b,404);
        call(delete("/api/places/"+place),b,404);
        String cumulative="{\"placeId\":\""+place+"\",\"assetIds\":[\""+asset+"\"]}";
        String run=field(call(post("/api/cumulative-runs").contentType(MediaType.APPLICATION_JSON).content(cumulative),a,201),"runId");
        call(get("/api/cumulative-runs/"+run),b,404);
        call(post("/api/cumulative-runs").contentType(MediaType.APPLICATION_JSON).content(cumulative),b,404);
        var listing=call(get("/api/assets"),b,200).getResponse().getContentAsString();
        assertTrue(listing.contains(other)); assertFalse(listing.contains(asset));
        String aId=field(call(get("/api/installations/current"),a,200),"installationId");
        call(get("/api/places").header("X-Installation-Id",aId),b,403);
        call(get("/api/assets").header("X-App-Api-Key","any"),null,401);
        // Legacy shared data must never become visible to the next installation to register.
        UUID legacy=UUID.randomUUID();
        assets.saveAndFlush(new ImageAsset(legacy,"image/png",1,legacy+".png",null,null,null,"unavailable",List.of(),"original",null,false));
        call(get("/api/assets/"+legacy),a,404);
        call(get("/api/inspection-batches/"+batch),a,200);
    }
    @Test void inviteLimitsExpiryAndRevocationAreEnforcedWithoutStoringRawCredentials() throws Exception {
        String code="CCCCCCCCCCCCCCCCCCCC";
        String token=enroll(code,1);
        call(post("/api/installations/enroll").contentType(MediaType.APPLICATION_JSON).content("{\"inviteCode\":\""+code+"\"}"),null,403);
        invites.saveAndFlush(new PilotInvite(PilotSessionService.hash("DDDDDDDDDDDDDDDDDDDD"),Instant.now().minusSeconds(1),1));
        call(post("/api/installations/enroll").contentType(MediaType.APPLICATION_JSON).content("{\"inviteCode\":\"DDDDDDDDDDDDDDDDDDDD\"}"),null,403);
        var id=UUID.fromString(field(call(get("/api/installations/current"),token,200),"installationId"));
        var record=installations.findById(id).orElseThrow();record.revoke();installations.saveAndFlush(record);
        call(get("/api/assets"),token,401);
        assertTrue(installations.findByTokenHash(token.substring(7)).isEmpty());
    }
    @Test void enrollmentAttemptsAreBounded() throws Exception {
        for(int i=0;i<10;i++) {
            var result=mvc.perform(post("/api/installations/enroll").with(req->{req.setRemoteAddr("192.0.2.90");return req;})
                    .contentType(MediaType.APPLICATION_JSON).content("{\"inviteCode\":\"EEEEEEEEEEEEEEEEEEEE\"}")).andReturn();
            assertEquals(403,result.getResponse().getStatus());
        }
        var limited=mvc.perform(post("/api/installations/enroll").with(req->{req.setRemoteAddr("192.0.2.90");return req;})
                .contentType(MediaType.APPLICATION_JSON).content("{\"inviteCode\":\"EEEEEEEEEEEEEEEEEEEE\"}")).andReturn();
        assertEquals(429,limited.getResponse().getStatus());
    }
    String enroll(String code,int uses) throws Exception {
        invites.saveAndFlush(new PilotInvite(PilotSessionService.hash(code),Instant.now().plusSeconds(3600),uses));
        return "Bearer "+field(call(post("/api/installations/enroll").contentType(MediaType.APPLICATION_JSON).content("{\"inviteCode\":\""+code+"\"}"),null,201),"token");
    }
    String upload(String token) throws Exception {
        return field(call(multipart("/api/assets").file(new MockMultipartFile("photo","synthetic.jpg","image/jpeg",new byte[]{(byte)255,(byte)216,(byte)255,0})),token,201),"assetId");
    }
    MvcResult call(AbstractMockHttpServletRequestBuilder<?> request,String token,int expected) throws Exception {
        if(token!=null)request.header("Authorization",token);
        request.with(req->{req.setRemoteAddr("192.0.2."+(++address));return req;});
        var result=mvc.perform(request).andReturn();assertEquals(expected,result.getResponse().getStatus());return result;
    }
    String field(MvcResult result,String name) throws Exception { return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(),"$."+name); }
}
