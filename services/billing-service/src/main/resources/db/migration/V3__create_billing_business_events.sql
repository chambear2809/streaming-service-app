CREATE TABLE billing_business_events (
    id UUID PRIMARY KEY,
    business_event_id UUID NOT NULL UNIQUE,
    event_type VARCHAR(64) NOT NULL,
    user_id UUID NOT NULL,
    invoice_id UUID,
    order_id UUID,
    subscription_id UUID,
    currency VARCHAR(8) NOT NULL,
    title VARCHAR(255) NOT NULL,
    description VARCHAR(1024),
    quantity INTEGER NOT NULL,
    unit_amount NUMERIC(12, 2) NOT NULL,
    tax_amount NUMERIC(12, 2) NOT NULL DEFAULT 0,
    discount_amount NUMERIC(12, 2) NOT NULL DEFAULT 0,
    issued_date DATE NOT NULL,
    due_date DATE NOT NULL,
    service_period_start DATE,
    service_period_end DATE,
    external_reference VARCHAR(128),
    notes VARCHAR(1024),
    applied_invoice_id UUID,
    applied_invoice_number VARCHAR(32),
    processing_status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_billing_business_events_user_id ON billing_business_events(user_id);
CREATE INDEX idx_billing_business_events_type ON billing_business_events(event_type);
CREATE INDEX idx_billing_business_events_invoice_id ON billing_business_events(invoice_id);
