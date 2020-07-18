/******************************************************************************
 * Product: Adempiere ERP & CRM Smart Business Solution                       *
 * Copyright (C) 1999-2006 ComPiere, Inc. All Rights Reserved.                *
 * This program is free software; you can redistribute it and/or modify it    *
 * under the terms version 2 of the GNU General Public License as published   *
 * by the Free Software Foundation. This program is distributed in the hope   *
 * that it will be useful, but WITHOUT ANY WARRANTY; without even the implied *
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.           *
 * See the GNU General Public License for more details.                       *
 * You should have received a copy of the GNU General Public License along    *
 * with this program; if not, write to the Free Software Foundation, Inc.,    *
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA.                     *
 * For the text or an alternative of this public license, you may reach us    *
 * ComPiere, Inc., 2620 Augustine Dr. #245, Santa Clara, CA 95054, USA        *
 * or via info@compiere.org or http://www.compiere.org/license.html           *
 *****************************************************************************/
package org.ecosoft.process;


import java.util.logging.Level;

import org.compiere.model.MPayment;
import org.compiere.process.ProcessInfoParameter;
import org.compiere.process.SvrProcess;
import org.compiere.util.AdempiereUserError;
import org.ecosoft.model.MCBilling;;

/**
 *	Process CreateLineFromBilling	
 *	
 *  @author Kitti U. - Ecosoft Co., Ltd.
 *	@version $Id: CreateLineFromBilling.java, v1 2010/12/31 06:53:26 ktu Exp $
 */

public class CreateLineFromBilling extends SvrProcess
{

	/** The Record						*/
	private int		p_C_Payment_ID = 0;
	/**	Billing ID				*/
	private int		p_C_Billing_ID = 0;
	
	/**
	 *  Prepare - e.g., get Parameters.
	 */
	protected void prepare()
	{
		ProcessInfoParameter[] para = getParameter();
		for (int i = 0; i < para.length; i++)
		{
			String name = para[i].getParameterName();
			if (para[i].getParameter() == null)
				;		
			if (name.equals("C_Billing_ID"))
				p_C_Billing_ID = para[i].getParameterAsInt();
			else
				log.log(Level.SEVERE, "Unknown Parameter: " + name);
		}
		p_C_Payment_ID = getRecord_ID();
	}	//	prepare

	/**
	 * 	Process
	 *	@return message
	 *	@throws Exception
	 */
	protected String doIt() throws Exception
	{
		int cnt = 0;
		
		log.info("C_Payment_ID=" + p_C_Payment_ID + ", C_Billing_ID=" + p_C_Billing_ID);
		
		MPayment pay = new MPayment (getCtx(), p_C_Payment_ID, get_TrxName());
		if (pay.get_ID() == 0)
			throw new IllegalArgumentException("Not found C_Payment_ID=" + p_C_Payment_ID);
				
		MCBilling bil = new MCBilling(getCtx(), p_C_Billing_ID, get_TrxName());
		if (bil.getC_Billing_ID() == 0)
			throw new AdempiereUserError("@No@ @Billing@");

		cnt = bil.createLineFromBilling(pay);
		
		if (cnt == -1)
			throw new AdempiereUserError("Error creating payment line from billing, please check log");

		return "@Inserted@=" + cnt;
	}	//	doIt
	
}	//	CreateLineFromBilling