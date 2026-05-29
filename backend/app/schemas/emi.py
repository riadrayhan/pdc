from pydantic import BaseModel, Field
from typing import Optional, List
from datetime import datetime, date
from decimal import Decimal
from uuid import UUID
from app.models.emi import ContractStatus, PaymentStatus


class EMIContractCreate(BaseModel):
    customer_id: UUID
    device_id: UUID
    product_name: str
    product_price: Decimal = Field(..., gt=0)
    down_payment: Decimal = Field(default=0, ge=0)
    interest_rate: Decimal = Field(default=0, ge=0, le=100)
    tenure_months: int = Field(..., gt=0, le=60)
    start_date: date
    emi_due_day: int = Field(default=1, ge=1, le=28)
    grace_period_days: int = Field(default=7, ge=0, le=30)
    notes: Optional[str] = None


class EMIContractUpdate(BaseModel):
    grace_period_days: Optional[int] = None
    notes: Optional[str] = None
    status: Optional[ContractStatus] = None


class EMIContractResponse(BaseModel):
    id: UUID
    contract_number: str
    customer_id: UUID
    device_id: UUID
    product_name: Optional[str] = None
    product_price: Decimal
    down_payment: Decimal
    principal_amount: Decimal
    interest_rate: Decimal
    tenure_months: int
    emi_amount: Decimal
    total_amount: Decimal
    start_date: date
    end_date: date
    emi_due_day: int
    status: ContractStatus
    grace_period_days: int
    total_paid: Decimal
    emis_paid: int
    notes: Optional[str] = None
    created_at: datetime

    class Config:
        from_attributes = True


class EMIContractListResponse(BaseModel):
    total: int
    contracts: List[EMIContractResponse]


class EMIPaymentCreate(BaseModel):
    paid_amount: Decimal = Field(..., gt=0)
    payment_method: str
    payment_reference: Optional[str] = None
    late_fee: Decimal = Field(default=0, ge=0)


class EMIPaymentResponse(BaseModel):
    id: UUID
    contract_id: UUID
    installment_number: int
    due_amount: Decimal
    paid_amount: Decimal
    late_fee: Decimal
    due_date: date
    paid_date: Optional[datetime] = None
    status: PaymentStatus
    payment_method: Optional[str] = None
    payment_reference: Optional[str] = None
    created_at: datetime

    class Config:
        from_attributes = True


class EMIPaymentListResponse(BaseModel):
    total: int
    payments: List[EMIPaymentResponse]


class OverdueReport(BaseModel):
    contract_id: UUID
    contract_number: str
    customer_name: str
    customer_phone: str
    device_imei: str
    due_date: date
    days_overdue: int
    amount_due: Decimal
    device_status: str
