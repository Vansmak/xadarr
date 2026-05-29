-- Scope Trakt sync_state per ARVIO profile so profiles with separate Trakt
-- accounts do not share last-activity cursors or sync progress.

create table if not exists public.sync_state (
  user_id uuid not null references auth.users(id) on delete cascade,
  profile_id text not null default 'default',
  last_sync_at timestamptz,
  last_full_sync_at timestamptz,
  last_trakt_activities text,
  last_trakt_activities_json text,
  movies_synced integer not null default 0,
  episodes_synced integer not null default 0,
  sync_in_progress boolean not null default false,
  last_error text,
  updated_at timestamptz not null default now(),
  primary key (user_id, profile_id)
);

alter table if exists public.sync_state
  add column if not exists profile_id text;

update public.sync_state
set profile_id = 'default'
where profile_id is null;

alter table if exists public.sync_state
  alter column profile_id set default 'default';

alter table if exists public.sync_state
  alter column profile_id set not null;

do $$
declare
  legacy_constraint record;
begin
  for legacy_constraint in
    select c.conname
    from pg_constraint c
    join pg_class t on t.oid = c.conrelid
    join pg_namespace n on n.oid = t.relnamespace
    where n.nspname = 'public'
      and t.relname = 'sync_state'
      and c.contype in ('u', 'p')
      and (
        select array_agg(a.attname order by a.attname)
        from unnest(c.conkey) key(attnum)
        join pg_attribute a on a.attrelid = t.oid and a.attnum = key.attnum
      ) = array['user_id']
  loop
    execute format('alter table public.sync_state drop constraint %I', legacy_constraint.conname);
  end loop;
end $$;

create unique index if not exists sync_state_user_profile_uidx
  on public.sync_state (user_id, profile_id);

create index if not exists sync_state_user_profile_updated_idx
  on public.sync_state (user_id, profile_id, updated_at desc);
