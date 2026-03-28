insert into billing_invoices (
    id,
    invoice_number,
    user_id,
    order_id,
    subscription_id,
    status,
    billing_cycle,
    currency,
    subtotal_amount,
    tax_amount,
    discount_amount,
    total_amount,
    balance_due,
    issued_date,
    due_date,
    service_period_start,
    service_period_end,
    external_payment_reference,
    notes
)
select *
from (
    values
        (
            '10000000-0000-0000-0000-000000000001'::uuid,
            'BILL-20260326-001',
            '8f61f6c0-29dc-4f6d-9a31-1fd4a4ad5001'::uuid,
            null::uuid,
            null::uuid,
            'OPEN',
            'MONTHLY',
            'USD',
            4200.00,
            0.00,
            0.00,
            4200.00,
            4200.00,
            current_date - 2,
            current_date + 12,
            current_date - 30,
            current_date - 1,
            null,
            'Regional carriage renewal for North Coast Sports Network.'
        ),
        (
            '10000000-0000-0000-0000-000000000002'::uuid,
            'BILL-20260326-002',
            '8f61f6c0-29dc-4f6d-9a31-1fd4a4ad5002'::uuid,
            null::uuid,
            null::uuid,
            'PAID',
            'MONTHLY',
            'USD',
            1850.00,
            0.00,
            0.00,
            1850.00,
            0.00,
            current_date - 18,
            current_date - 4,
            current_date - 45,
            current_date - 15,
            'ACH-SETTLED-20260312',
            'Always-on forecast service settled by ACH.'
        ),
        (
            '10000000-0000-0000-0000-000000000003'::uuid,
            'BILL-20260326-003',
            '8f61f6c0-29dc-4f6d-9a31-1fd4a4ad5004'::uuid,
            null::uuid,
            null::uuid,
            'OPEN',
            'ONE_TIME',
            'USD',
            960.00,
            0.00,
            0.00,
            960.00,
            960.00,
            current_date - 16,
            current_date - 1,
            current_date - 16,
            current_date - 1,
            null,
            'Pop-up event contribution window awaiting settlement.'
        )
) as seeded(
    id,
    invoice_number,
    user_id,
    order_id,
    subscription_id,
    status,
    billing_cycle,
    currency,
    subtotal_amount,
    tax_amount,
    discount_amount,
    total_amount,
    balance_due,
    issued_date,
    due_date,
    service_period_start,
    service_period_end,
    external_payment_reference,
    notes
)
where not exists (
    select 1 from billing_invoices existing where existing.id = seeded.id
);

insert into billing_invoice_line_items (
    id,
    invoice_id,
    title,
    description,
    quantity,
    unit_amount,
    line_total,
    service_period_start,
    service_period_end
)
select *
from (
    values
        (
            '20000000-0000-0000-0000-000000000001'::uuid,
            '10000000-0000-0000-0000-000000000001'::uuid,
            'Regional carriage package',
            'Prime East and replay rights for March operations.',
            1,
            4200.00,
            4200.00,
            current_date - 30,
            current_date - 1
        ),
        (
            '20000000-0000-0000-0000-000000000002'::uuid,
            '10000000-0000-0000-0000-000000000002'::uuid,
            'Forecast service subscription',
            'Metro Weather Desk continuous forecast feed.',
            1,
            1850.00,
            1850.00,
            current_date - 45,
            current_date - 15
        ),
        (
            '20000000-0000-0000-0000-000000000003'::uuid,
            '10000000-0000-0000-0000-000000000003'::uuid,
            'Event contribution window',
            'Temporary live-event contribution circuit.',
            1,
            960.00,
            960.00,
            current_date - 16,
            current_date - 1
        )
) as seeded(
    id,
    invoice_id,
    title,
    description,
    quantity,
    unit_amount,
    line_total,
    service_period_start,
    service_period_end
)
where not exists (
    select 1 from billing_invoice_line_items existing where existing.id = seeded.id
);
