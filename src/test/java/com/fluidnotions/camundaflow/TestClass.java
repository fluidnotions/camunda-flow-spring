package com.fluidnotions.camundaflow;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class TestClass{
    record ProcessContextQuote(Long supplierkey, String supplierreference, String quotenumber, Long versione,
                               Long createdby, Long orderrequestkey, Boolean cancellationrequested, Long id,
                               Long orderrequesttypekey, LocalDateTime createdon, Long quotetypekey,
                               BigDecimal buildcost) {
    }
}


