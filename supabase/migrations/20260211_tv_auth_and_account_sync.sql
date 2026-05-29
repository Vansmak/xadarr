-- Device auth sessions + cloud account state
create table if not exists public.tv_device_auth_sessions (
  id uuid primary key default gen_random_uuid(),
  device_code text not null unique,
  user_code text not null unique,
  status text not null default 'pending' check (status in ('pending','approved','consumed','expired')),
  access_token text,
  refresh_token text,
  user_id uuid references auth.users(id) on delete set null,
  user_email text,
  expires_at timestamptz not null default (now() + interval '10 minutes'),
  approved_at timestamptz,
  consumed_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index if not exists idx_tv_device_auth_sessions_status_expires
  on public.tv_device_auth_sessions(status, expires_at);

alter table public.tv_device_auth_sessions enable row level security;
alter table public.tv_device_auth_sessions force row level security;

do $$
begin
  if not exists (
    select 1
    from pg_policies
    where schemaname = 'public'
      and tablename = 'tv_device_auth_sessions'
      and policyname = 'deny_all_device_auth_sessions'
  ) then
    create policy deny_all_device_auth_sessions
      on public.tv_device_auth_sessions
      for all
      to anon, authenticated
      using (false)
      with check (false);
  end if;
end $$;

create table if not exists public.account_sync_state (
  user_id uuid primary key references auth.users(id) on delete cascade,
  payload text not null,
  updated_at timestamptz not null default now()
);

alter table public.account_sync_state enable row level security;
alter table public.account_sync_state force row level security;

do $$
begin
  if not exists (
    select 1
    from pg_policies
    where schemaname = 'public'
      and tablename = 'account_sync_state'
      and policyname = 'users_manage_own_account_sync_state'
  ) then
    create policy users_manage_own_account_sync_state
      on public.account_sync_state
      for all
      to authenticated
      using ((select auth.uid()) = user_id)
      with check ((select auth.uid()) = user_id);
  end if;
end $$;

create or replace function public.cleanup_expired_tv_device_auth_sessions()
returns void
language sql
security definer
set search_path = public, pg_catalog
as $$
  delete from public.tv_device_auth_sessions
  where expires_at < now() - interval '1 day'
     or status in ('consumed','expired') and updated_at < now() - interval '30 minutes';
$$;

