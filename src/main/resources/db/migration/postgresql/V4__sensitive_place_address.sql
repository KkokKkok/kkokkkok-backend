-- Map label the owner picked for a sensitive place; returned only to the authenticated owner.
alter table sensitive_places add column address varchar(200);
