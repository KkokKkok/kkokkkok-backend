package com.kkokkkok.backend.service;

import com.kkokkkok.backend.apiPayload.exception.ApiFailure;
import com.kkokkkok.backend.client.AnalysisClient;
import com.kkokkkok.backend.domain.*;
import com.kkokkkok.backend.repository.*;
import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"app.pilot.enabled=true","spring.datasource.url=jdbc:h2:mem:delete-test;DB_CLOSE_DELAY=-1","assets.storage-root=build/deletion-test-assets"})
class DataDeletionServiceTest {
    @Autowired DataDeletionService service;
    @Autowired ImageAssetRepository assets;
    @Autowired InspectionRepository inspections;
    @Autowired EditRunRepository edits;
    @Autowired InspectionBatchRepository batches;
    @Autowired InspectionBatchItemRepository items;
    @Autowired SensitivePlaceRepository places;
    @Autowired PlaceAssetLinkRepository links;
    @Autowired CumulativeRunRepository cumulative;
    @Autowired AssetStorage storage;
    @Autowired FakeAnalysis remote;
    UUID owner;
    @BeforeEach void setup() {
        links.deleteAll(); cumulative.deleteAll(); places.deleteAll(); items.deleteAll(); batches.deleteAll();
        edits.deleteAll(); inspections.deleteAll(); assets.deleteAll(); remote.status.clear(); remote.fail=false;
        owner=UUID.randomUUID(); var request=new MockHttpServletRequest(); request.setAttribute(PilotOwnership.ATTRIBUTE,owner);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
    @AfterEach void teardown() { RequestContextHolder.resetRequestAttributes(); }
    ImageAsset photo(UUID who) {
        UUID id=UUID.randomUUID(); String key=storage.save(id,new byte[]{1,2,3},"image/png");
        var asset=new ImageAsset(id,"image/png",3,key,null,null,null,"missing",List.of("합성"),"original",null,false);
        asset.assignOwner(who); return assets.save(asset);
    }
    Inspection inspection(ImageAsset asset,String status) {
        UUID id=UUID.randomUUID(); var value=new Inspection(id,"f",asset.getId(),null); value.update(id,status);
        remote.status.put(id,status); return inspections.save(value);
    }
    @Test void deletesOwnedFilesAndAllRelatedRecordsAndCanRepeatWithoutTouchingOtherOwner() {
        var source=photo(owner); var other=photo(UUID.randomUUID()); var inspected=inspection(source,"completed");
        var otherInspection=inspection(other,"completed");
        var edit=new EditRun(UUID.randomUUID(),"f",inspected.getInspectionId(),source.getId(),List.of("c"));
        edit.complete(source.getId(),false,"synthetic",null); edits.save(edit);
        UUID batchId=UUID.randomUUID(); batches.save(new InspectionBatch(batchId,"f"));
        items.save(new InspectionBatchItem(UUID.randomUUID(),batchId,0,source.getId()));
        var place=places.save(new SensitivePlace(UUID.randomUUID(),owner,"합성 집","home",0,0,100));
        links.save(new PlaceAssetLink(place.getId(),source.getId())); cumulative.save(new CumulativeRun(UUID.randomUUID(),place.getId(),"test","{}"));
        service.delete(UUID.randomUUID()); service.delete(owner);
        assertFalse(assets.existsById(source.getId())); assertTrue(assets.existsById(other.getId()));
        assertEquals(1,inspections.count()); assertTrue(remote.status.containsKey(otherInspection.getInspectionId()));
        assertFalse(remote.status.containsKey(inspected.getInspectionId()));
        assertEquals(0,edits.count()); assertEquals(0,items.count()); assertEquals(0,batches.count());
        assertEquals(0,places.count()); assertEquals(0,links.count()); assertEquals(0,cumulative.count());
        assertFalse(Files.exists(Path.of("build/deletion-test-assets").resolve(source.getStorageKey())));
        assertArrayEquals(new byte[]{1,2,3},storage.read(other.getStorageKey())); storage.delete(other.getStorageKey());
    }
    @Test void blocksActiveWorkAndRetainsMetadataOnExternalFailureForRetry() {
        var source=photo(owner); var inspection=inspection(source,"processing");
        assertEquals(409,assertThrows(ApiFailure.class,()->service.delete(owner)).status);
        assertTrue(assets.existsById(source.getId())); assertTrue(remote.status.containsKey(inspection.getInspectionId()));
        remote.status.put(inspection.getInspectionId(),"completed"); remote.fail=true;
        assertThrows(ApiFailure.class,()->service.delete(owner)); assertTrue(assets.existsById(source.getId()));
        remote.fail=false; service.delete(owner); assertFalse(assets.existsById(source.getId()));
    }
    @TestConfiguration static class Configuration {
        @Bean @Primary FakeAnalysis fakeAnalysis() { return new FakeAnalysis(); }
    }
    static class FakeAnalysis implements AnalysisClient {
        final Map<UUID,String> status=new HashMap<>(); boolean fail;
        public Map<String,Object> create(InspectionSubmission s,UUID id) { throw new UnsupportedOperationException(); }
        public Map<String,Object> get(UUID id) { if(!status.containsKey(id)) throw new ApiFailure(404,"MISSING","missing"); return Map.of("status",status.get(id)); }
        public void delete(UUID id) { if(fail) throw new ApiFailure(502,"UNAVAILABLE","synthetic failure"); status.remove(id); }
    }
}
