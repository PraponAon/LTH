CREATE OR REPLACE VIEW adempiere.LTH_C_Invoice_Header_v
AS SELECT i.ad_client_id,
    i.ad_org_id,
    i.isactive,
    i.created,
    i.createdby,
    i.updated,
    i.updatedby,
    'en_US'::character varying AS ad_language,
    i.c_invoice_id,
    i.issotrx,
    i.documentno,
    i.docstatus,
    i.c_doctype_id,
    i.c_bpartner_id,
    bp.value AS bpvalue,
    bp.taxid AS bptaxid,
    bp.naics,
    bp.duns,
    oi.c_location_id AS org_location_id,
    oi.taxid,
    dt.printname AS documenttype,
    dt.documentnote AS documenttypenote,
    i.c_order_id,
    i.salesrep_id,
    COALESCE(ubp.name, u.name) AS salesrep_name,
    i.dateinvoiced,
    bpg.greeting AS bpgreeting,
    bp.name,
    bp.name2,
    bpcg.greeting AS bpcontactgreeting,
    bpc.title,
    bpc.phone,
    NULLIF(bpc.name::text, bp.name::text) AS contactname,
    bpl.name AS bplocationname,
    bpl.c_location_id,
    bpl2.c_location_id AS ship_c_location_id,
    bp.referenceno,
    l.postal::text || l.postal_add::text AS postal,
    i.description,
    i.poreference,
    i.dateordered,
    i.c_currency_id,
    pt.name AS paymentterm,
    pt.documentnote AS paymenttermnote,
    i.dateinvoiced::timestamp with time zone + pt.netdays AS duedate,
    i.c_charge_id,
    i.chargeamt,
    i.totallines,
    i.grandtotal,
    i.grandtotal AS amtinwords,
    i.m_pricelist_id,
    i.istaxincluded,
    i.c_campaign_id,
    i.c_project_id,
    i.c_activity_id,
    i.ispaid,
    COALESCE(oi.logo_id, ci.logo_id) AS logo_id,
    tx.taxamt,
    txr.rate
   FROM c_invoice i
     JOIN c_doctype dt ON i.c_doctype_id = dt.c_doctype_id
     JOIN c_paymentterm pt ON i.c_paymentterm_id = pt.c_paymentterm_id
     JOIN c_bpartner bp ON i.c_bpartner_id = bp.c_bpartner_id
     LEFT JOIN c_greeting bpg ON bp.c_greeting_id = bpg.c_greeting_id
     JOIN c_bpartner_location bpl ON i.c_bpartner_location_id = bpl.c_bpartner_location_id
     JOIN c_location l ON bpl.c_location_id = l.c_location_id
     LEFT JOIN c_order o ON i.c_order_id = o.c_order_id
     LEFT JOIN c_bpartner_location bpl2 ON o.c_bpartner_location_id = bpl2.c_bpartner_location_id
     LEFT JOIN ad_user bpc ON i.ad_user_id = bpc.ad_user_id
     LEFT JOIN c_greeting bpcg ON bpc.c_greeting_id = bpcg.c_greeting_id
     JOIN ad_orginfo oi ON i.ad_org_id = oi.ad_org_id
     JOIN ad_clientinfo ci ON i.ad_client_id = ci.ad_client_id
     LEFT JOIN ad_user u ON i.salesrep_id = u.ad_user_id
     LEFT JOIN c_bpartner ubp ON u.c_bpartner_id = ubp.c_bpartner_id
     LEFT JOIN ( SELECT c_invoicetax.c_invoice_id,
            sum(c_invoicetax.taxamt) AS taxamt
           FROM c_invoicetax
          GROUP BY c_invoicetax.c_invoice_id) tx ON i.c_invoice_id = tx.c_invoice_id
     LEFT JOIN ( SELECT DISTINCT x.c_invoice_id,
            c.rate
           FROM c_invoicetax x
             LEFT JOIN c_tax c ON x.c_tax_id = c.c_tax_id) txr ON txr.c_invoice_id = i.c_invoice_id;