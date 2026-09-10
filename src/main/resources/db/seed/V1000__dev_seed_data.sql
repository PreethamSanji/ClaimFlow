-- Dev-only sample data (loaded by the "dev" profile). 3 customers, 4 policies, 6 claims.

INSERT INTO customer (full_name, email, created_at) VALUES
    ('Asha Rao',      'asha.rao@example.com',      '2026-01-02T09:00:00Z'),
    ('Daniel Okafor', 'daniel.okafor@example.com', '2026-01-05T11:30:00Z'),
    ('Mei Lin',       'mei.lin@example.com',       '2026-01-10T14:15:00Z');

INSERT INTO policy (policy_number, customer_id, type, coverage_limit, deductible, start_date, end_date, status) VALUES
    ('POL-2026-000001', (SELECT id FROM customer WHERE email = 'asha.rao@example.com'),
        'AUTO',      25000.00,  500.00, '2026-01-15', '2027-01-14', 'ACTIVE'),
    ('POL-2026-000002', (SELECT id FROM customer WHERE email = 'asha.rao@example.com'),
        'HOME',     300000.00, 1000.00, '2026-02-01', '2027-01-31', 'ACTIVE'),
    ('POL-2026-000003', (SELECT id FROM customer WHERE email = 'daniel.okafor@example.com'),
        'PROPERTY', 150000.00, 2500.00, '2026-03-01', '2027-02-28', 'ACTIVE'),
    ('POL-2026-000004', (SELECT id FROM customer WHERE email = 'mei.lin@example.com'),
        'AUTO',      15000.00,  750.00, '2025-06-01', '2026-05-31', 'LAPSED');

-- Move the sequence past the numbers used above.
SELECT setval('policy_number_seq', 4);

INSERT INTO claim (claim_number, policy_id, incident_date, reported_at, description,
                   claimed_amount, approved_amount, status) VALUES
    ('CLM-2026-000001', (SELECT id FROM policy WHERE policy_number = 'POL-2026-000001'),
        '2026-08-28', '2026-08-29T10:00:00Z', 'Rear-ended at a traffic light', 3200.00, NULL, 'FNOL'),
    ('CLM-2026-000002', (SELECT id FROM policy WHERE policy_number = 'POL-2026-000002'),
        '2026-07-10', '2026-07-11T08:30:00Z', 'Water damage from burst kitchen pipe', 8750.00, NULL, 'UNDER_REVIEW'),
    ('CLM-2026-000003', (SELECT id FROM policy WHERE policy_number = 'POL-2026-000003'),
        '2026-06-02', '2026-06-03T16:45:00Z', 'Warehouse fire destroyed inventory', 180000.00, NULL,
        'FLAGGED_FOR_INVESTIGATION'),
    ('CLM-2026-000004', (SELECT id FROM policy WHERE policy_number = 'POL-2026-000001'),
        '2026-04-20', '2026-04-21T12:00:00Z', 'Windshield cracked by road debris', 1200.00, 700.00, 'APPROVED'),
    ('CLM-2026-000005', (SELECT id FROM policy WHERE policy_number = 'POL-2026-000003'),
        '2026-03-20', '2026-03-22T09:10:00Z', 'Storm damage to roof', 12000.00, 9500.00, 'PAID'),
    ('CLM-2026-000006', (SELECT id FROM policy WHERE policy_number = 'POL-2026-000004'),
        '2026-02-14', '2026-02-15T18:20:00Z', 'Minor scratch in parking lot', 400.00, NULL, 'REJECTED');

SELECT setval('claim_number_seq', 6);

INSERT INTO claim_fraud_flag (claim_id, rule_name, reason) VALUES
    ((SELECT id FROM claim WHERE claim_number = 'CLM-2026-000003'),
        'AMOUNT_EXCEEDS_COVERAGE', 'Claimed amount 180000.00 exceeds coverage limit 150000.00');

-- Audit trail matching each claim's current status.
INSERT INTO claim_event (claim_id, from_status, to_status, reason, actor, occurred_at)
SELECT c.id, e.from_status, e.to_status, e.reason, e.actor, e.occurred_at::timestamptz
FROM (VALUES
    ('CLM-2026-000001', NULL,           'FNOL',                      'First notice of loss', 'api-user',            '2026-08-29T10:00:00Z'),
    ('CLM-2026-000002', NULL,           'FNOL',                      'First notice of loss', 'api-user',            '2026-07-11T08:30:00Z'),
    ('CLM-2026-000002', 'FNOL',         'UNDER_REVIEW',              'Assigned to adjuster', 'adjuster.kim',        '2026-07-12T09:00:00Z'),
    ('CLM-2026-000003', NULL,           'FNOL',                      'First notice of loss', 'api-user',            '2026-06-03T16:45:00Z'),
    ('CLM-2026-000003', 'FNOL',         'UNDER_REVIEW',              'Assigned to adjuster', 'adjuster.kim',        '2026-06-04T10:00:00Z'),
    ('CLM-2026-000003', 'UNDER_REVIEW', 'FLAGGED_FOR_INVESTIGATION', 'Auto-flagged by fraud rules: AMOUNT_EXCEEDS_COVERAGE',
                                                                                              'system:fraud-engine', '2026-06-04T10:00:00Z'),
    ('CLM-2026-000004', NULL,           'FNOL',                      'First notice of loss', 'api-user',            '2026-04-21T12:00:00Z'),
    ('CLM-2026-000004', 'FNOL',         'UNDER_REVIEW',              'Assigned to adjuster', 'adjuster.kim',        '2026-04-22T09:00:00Z'),
    ('CLM-2026-000004', 'UNDER_REVIEW', 'APPROVED',                  'Photos confirm damage', 'adjuster.kim',       '2026-04-25T15:00:00Z'),
    ('CLM-2026-000005', NULL,           'FNOL',                      'First notice of loss', 'api-user',            '2026-03-22T09:10:00Z'),
    ('CLM-2026-000005', 'FNOL',         'UNDER_REVIEW',              'Assigned to adjuster', 'adjuster.lee',        '2026-03-23T09:00:00Z'),
    ('CLM-2026-000005', 'UNDER_REVIEW', 'APPROVED',                  'Roofer estimate verified', 'adjuster.lee',    '2026-03-30T11:00:00Z'),
    ('CLM-2026-000005', 'APPROVED',     'PAID',                      'Payment sent',         'finance.bot',         '2026-04-02T08:00:00Z'),
    ('CLM-2026-000006', NULL,           'FNOL',                      'First notice of loss', 'api-user',            '2026-02-15T18:20:00Z'),
    ('CLM-2026-000006', 'FNOL',         'UNDER_REVIEW',              'Assigned to adjuster', 'adjuster.lee',        '2026-02-16T09:00:00Z'),
    ('CLM-2026-000006', 'UNDER_REVIEW', 'REJECTED',                  'Loss is below the deductible', 'adjuster.lee', '2026-02-17T10:00:00Z')
) AS e(claim_number, from_status, to_status, reason, actor, occurred_at)
JOIN claim c ON c.claim_number = e.claim_number;
