import uuid
from datetime import datetime, date
from sqlalchemy import Column, String, DateTime, Date, Integer, Numeric, Enum as SQLEnum, ForeignKey, Text
from app.core.types import UUID
from sqlalchemy.orm import relationship
import enum

from app.core.database import Base


class ContractStatus(str, enum.Enum):
    ACTIVE = "active"
    COMPLETED = "completed"
    DEFAULTED = "defaulted"
    CANCELLED = "cancelled"


class PaymentStatus(str, enum.Enum):
    PENDING = "pending"
    PAID = "paid"
    PARTIAL = "partial"
    OVERDUE = "overdue"
    WAIVED = "waived"


class EMIContract(Base):
    __tablename__ = "emi_contracts"
    
    id = Column(UUID(), primary_key=True, default=uuid.uuid4)
    contract_number = Column(String(50), unique=True, nullable=False, index=True)
    
    # Customer and Device
    customer_id = Column(UUID(), ForeignKey("customers.id"), nullable=False)
    device_id = Column(UUID(), ForeignKey("devices.id"), nullable=False)
    
    # Product Details
    product_name = Column(String(255))
    product_price = Column(Numeric(12, 2), nullable=False)
    down_payment = Column(Numeric(12, 2), default=0)
    
    # EMI Details
    principal_amount = Column(Numeric(12, 2), nullable=False)
    interest_rate = Column(Numeric(5, 2), default=0)  # Annual interest rate
    tenure_months = Column(Integer, nullable=False)
    emi_amount = Column(Numeric(10, 2), nullable=False)
    total_amount = Column(Numeric(12, 2), nullable=False)
    
    # Dates
    start_date = Column(Date, nullable=False)
    end_date = Column(Date, nullable=False)
    emi_due_day = Column(Integer, default=1)  # Day of month when EMI is due
    
    # Status
    status = Column(SQLEnum(ContractStatus), default=ContractStatus.ACTIVE, index=True)
    grace_period_days = Column(Integer, default=7)
    
    # Paid Amount Tracking
    total_paid = Column(Numeric(12, 2), default=0)
    emis_paid = Column(Integer, default=0)
    
    # Notes
    notes = Column(Text)
    
    # Relations
    customer = relationship("Customer", back_populates="contracts")
    device = relationship("Device")
    payments = relationship("EMIPayment", back_populates="contract")
    
    # Timestamps
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)
    created_by = Column(UUID(), ForeignKey("users.id"))
    
    def __repr__(self):
        return f"<EMIContract {self.contract_number}>"


class EMIPayment(Base):
    __tablename__ = "emi_payments"
    
    id = Column(UUID(), primary_key=True, default=uuid.uuid4)
    
    contract_id = Column(UUID(), ForeignKey("emi_contracts.id"), nullable=False)
    installment_number = Column(Integer, nullable=False)
    
    # Amount
    due_amount = Column(Numeric(10, 2), nullable=False)
    paid_amount = Column(Numeric(10, 2), default=0)
    late_fee = Column(Numeric(10, 2), default=0)
    
    # Dates
    due_date = Column(Date, nullable=False, index=True)
    paid_date = Column(DateTime)
    
    # Status
    status = Column(SQLEnum(PaymentStatus), default=PaymentStatus.PENDING, index=True)
    
    # Payment Reference
    payment_method = Column(String(50))  # cash, upi, bank_transfer, etc.
    payment_reference = Column(String(100))
    
    # Relations
    contract = relationship("EMIContract", back_populates="payments")
    
    # Timestamps
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)
    recorded_by = Column(UUID(), ForeignKey("users.id"))
    
    def __repr__(self):
        return f"<EMIPayment {self.contract_id} - {self.installment_number}>"
