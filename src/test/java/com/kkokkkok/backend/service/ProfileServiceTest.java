package com.kkokkkok.backend.service;

import com.kkokkkok.backend.domain.*;
import com.kkokkkok.backend.repository.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"app.pilot.enabled=true","spring.datasource.url=jdbc:h2:mem:profile-test;DB_CLOSE_DELAY=-1"})
@Transactional
class ProfileServiceTest {
    @Autowired ImageAssetRepository assets;
    @Autowired InspectionRepository inspections;
    @Autowired EditRunRepository edits;
    @Autowired SensitivePlaceRepository places;
    @Autowired CumulativeRunRepository cumulative;
    @Autowired PilotOwnership ownership;
    @Test void countsOwnedCompletedWorkWithoutCountingVerificationAndExposesIndefinitePolicy() {
        UUID owner=UUID.randomUUID(), source=UUID.randomUUID(), edited=UUID.randomUUID(), other=UUID.randomUUID();
        var original=new ImageAsset(source,"image/png",10,"synthetic.png",null,null,null,"missing",List.of(),"original",null,false);
        original.assignOwner(owner); assets.save(original);
        var result=new ImageAsset(edited,"image/png",10,"synthetic-edited.png",null,null,null,"missing",List.of(),"edited",source,false);
        result.assignOwner(owner); assets.save(result);
        org.springframework.mock.web.MockHttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setAttribute(PilotOwnership.ATTRIBUTE,owner);
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(request));
        try {
        var complete=new Inspection(UUID.randomUUID(),"f",source,null); complete.update(UUID.randomUUID(),"completed");
        var failed=new Inspection(UUID.randomUUID(),"f",source,null); failed.update(UUID.randomUUID(),"failed");
        var verification=new Inspection(UUID.randomUUID(),"f",edited,null); verification.update(UUID.randomUUID(),"completed");
        var another=new Inspection(UUID.randomUUID(),"f",other,null); another.update(UUID.randomUUID(),"completed");
        inspections.saveAll(List.of(complete,failed,verification,another));
        var edit=new EditRun(UUID.randomUUID(),"f",complete.getInspectionId(),source,List.of("c")); edit.complete(edited,false,"test",null);
        edits.save(edit);
        var place=new SensitivePlace(UUID.randomUUID(),owner,"합성 집","home",0,0,300);
        places.save(place);
        cumulative.saveAll(List.of(new CumulativeRun(UUID.randomUUID(),place.getId(),"test","{}"),
                new CumulativeRun(UUID.randomUUID(),UUID.randomUUID(),"test","{}")));
        var response=new ProfileService(assets,inspections,edits,places,cumulative,ownership).overview(other);
        assertEquals(1L,response.get("photoChecks")); assertEquals(1L,response.get("protectionEdits"));
        assertEquals(1L,response.get("cumulativeChecks"));
        assertEquals(Map.of("autoDeleteOriginals",false,"retention","until_user_deletes"),response.get("storagePolicy"));
        } finally { org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes(); }
    }
}
