CREATE TABLE billing_invoices (
    id UUID PRIMARY KEY,
    invoice_number VARCHAR(32) NOT NULL UNIQUE,
    user_id UUID NOT NULL,
    order_id UUID,
    subscription_id UUID,
    status VARCHAR(32) NOT NULL,
    billing_cycle VARCHAR(32) NOT NULL,
    currency VARCHAR(8) NOT NULL,
    subtotal_amount NUMERIC(12, 2) NOT NULL DEFAULT 0,
    tax_amount NUMERIC(12, 2) NOT NULL DEFAULT 0,
    discount_amount NUMERIC(12, 2) NOT NULL DEFAULT 0,
    total_amount NUMERIC(12, 2) NOT NULL DEFAULT 0,
    balance_due NUMERIC(12, 2) NOT NULL DEFAULT 0,
    issued_date DATE NOT NULL,
    due_date DATE NOT NULL,
    service_period_start DATE,
    service_period_end DATE,
    external_payment_reference VARCHAR(128),
    notes VARCHAR(1024),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_billing_invoices_user_id ON billing_invoices(user_id);
CREATE INDEX idx_billing_invoices_status ON billing_invoices(status);
CREATE INDEX idx_billing_invoices_due_date ON billing_invoices(due_date);

CREATE TABLE billing_invoice_line_items (
    id UUID PRIMARY KEY,
    invoice_id UUID NOT NULL REFERENCES billing_invoices(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    description VARCHAR(1024),
    quantity INTEGER NOT NULL,
    unit_amount NUMERIC(12, 2) NOT NULL,
    line_total NUMERIC(12, 2) NOT NULL,
    service_period_start DATE,
    service_period_end DATE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_billing_line_items_invoice_id ON billing_invoice_line_items(invoice_id);
