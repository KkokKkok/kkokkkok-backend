alter table image_assets add column owner_installation_id uuid;
create index ix_assets_owner_created on image_assets(owner_installation_id, created_at desc);
create table pilot_invites (
 code_hash varchar(64) primary key, expires_at timestamptz(6) not null, remaining_uses integer not null
);
create table pilot_installations (
 id uuid primary key, token_hash varchar(64) not null unique,
 expires_at timestamptz(6) not null, revoked boolean not null
);
