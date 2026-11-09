-- Empty PostgreSQL deployment database only. Existing H2 files are not imported or modified here.
create table image_assets (
 id uuid primary key, content_type varchar(32) not null, size_bytes bigint not null,
 storage_key varchar(160) not null, asset_role varchar(16) not null, parent_asset_id uuid,
 mock boolean not null, latitude double precision, longitude double precision,
 manual_latitude double precision, manual_longitude double precision,
 captured_at timestamp(6), location_status varchar(24) not null, created_at timestamptz(6) not null
);
create table image_asset_labels (
 asset_id uuid not null references image_assets(id), label varchar(40) not null
);
create table inspections (
 request_id uuid primary key, fingerprint varchar(64) not null, inspection_id uuid unique,
 asset_id uuid, location_fallback varchar(120), status varchar(32) not null,
 created_at timestamptz(6) not null, updated_at timestamptz(6) not null, version bigint
);
create table inspection_batches (
 batch_id uuid primary key, fingerprint varchar(64) not null, created_at timestamptz(6) not null
);
create table inspection_batch_items (
 item_id uuid primary key, batch_id uuid not null, position integer not null, asset_id uuid not null,
 inspection_id uuid, status varchar(32) not null, mock boolean not null,
 error_code varchar(64), error_message varchar(240),
 created_at timestamptz(6) not null, updated_at timestamptz(6) not null
);
create table edit_runs (
 edit_id uuid primary key, fingerprint varchar(64) not null, inspection_id uuid not null,
 source_asset_id uuid not null, edited_asset_id uuid, verification_inspection_id uuid,
 verification_status varchar(24), status varchar(24) not null, mock boolean not null,
 mock_notice varchar(240), provider_version varchar(48),
 created_at timestamptz(6) not null, updated_at timestamptz(6) not null
);
create table edit_run_cues (
 edit_id uuid not null references edit_runs(edit_id), cue_id varchar(100) not null
);
create table sensitive_places (
 id uuid primary key, installation_id uuid not null, name varchar(80) not null,
 category varchar(16) not null, latitude double precision not null, longitude double precision not null,
 radius_meters integer not null, created_at timestamptz(6) not null
);
create table place_asset_links (
 id uuid primary key, place_id uuid not null, asset_id uuid not null, created_at timestamptz(6) not null,
 constraint uk_place_asset_pair unique (place_id, asset_id)
);
create table cumulative_runs (
 id uuid primary key, place_id uuid not null, algorithm_version varchar(80) not null,
 result_json text not null, created_at timestamptz(6) not null
);
create index ix_inspections_asset_created on inspections(asset_id, created_at desc);
create index ix_batch_items_batch_position on inspection_batch_items(batch_id, position);
create index ix_places_installation on sensitive_places(installation_id);
create index ix_cumulative_place_created on cumulative_runs(place_id, created_at desc);
create index ix_place_links_asset on place_asset_links(asset_id);
