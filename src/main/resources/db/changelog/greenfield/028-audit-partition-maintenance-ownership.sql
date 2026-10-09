--liquibase formatted sql

--changeset akmai-greenfield:028-audit-partition-maintenance-ownership splitStatements:false
CREATE OR REPLACE FUNCTION akmai_admin.maintain_audit_partitions()
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, akmai_admin
AS $$
DECLARE
    v_offset INTEGER;
    v_cutoff DATE;
    v_partition RECORD;
BEGIN
    -- This function is invoked by every application replica at startup and on
    -- schedule. Only one transaction may execute partition DDL at a time.
    -- pg_try_advisory_xact_lock is PostgreSQL-scoped authority and is released
    -- automatically when the caller transaction commits or rolls back.
    IF NOT pg_try_advisory_xact_lock(
        hashtext('akmai.audit.partition-maintenance')::bigint
    ) THEN
        RETURN;
    END IF;

    FOR v_offset IN 0..2 LOOP
        PERFORM akmai_admin.ensure_audit_month_partition(
            (date_trunc('month', clock_timestamp())
                + (v_offset * interval '1 month'))::date
        );
    END LOOP;

    v_cutoff := (
        date_trunc('month', clock_timestamp()) - interval '11 months'
    )::date;

    FOR v_partition IN
        SELECT child.relname AS relation_name
        FROM pg_inherits inheritance
        JOIN pg_class parent
          ON parent.oid = inheritance.inhparent
        JOIN pg_class child
          ON child.oid = inheritance.inhrelid
        JOIN pg_namespace namespace
          ON namespace.oid = child.relnamespace
        WHERE parent.oid = 'public.knowledge_audit_event'::regclass
          AND namespace.nspname = 'public'
          AND child.relname ~ '^knowledge_audit_event_[0-9]{4}_[0-9]{2}$'
          AND to_date(
                substring(child.relname from '([0-9]{4}_[0-9]{2})$'),
                'YYYY_MM'
              ) < v_cutoff
    LOOP
        EXECUTE format(
            'DROP TABLE public.%I',
            v_partition.relation_name
        );
    END LOOP;
END;
$$;
