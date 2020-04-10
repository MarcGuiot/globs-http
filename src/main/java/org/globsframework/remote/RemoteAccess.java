/**
*  Copyright Murex S.A.S., 2003-2013. All Rights Reserved.
*
*  This software program is proprietary and confidential to Murex S.A.S and its affiliates ("Murex") and, without limiting the generality of the foregoing reservation of rights, shall not be accessed, used, reproduced or distributed without the
*  express prior written consent of Murex and subject to the applicable Murex licensing terms. Any modification or removal of this copyright notice is expressly prohibited.
*/
package org.globsframework.remote;

import java.io.InputStream;
import java.io.OutputStream;

public interface RemoteAccess {
    interface ReadStream {
        OutputStream getOutputStream();

        InputStream complete();
    }

    ReadStream sendRequest();

    ReadStream sendChanges();

    ReadStream sendIdsRequest();
}
