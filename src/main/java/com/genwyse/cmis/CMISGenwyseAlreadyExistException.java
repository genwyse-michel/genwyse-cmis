package com.genwyse.cmis;

import org.apache.chemistry.opencmis.commons.exceptions.CmisContentAlreadyExistsException;

public class CMISGenwyseAlreadyExistException extends CMISGenwyseException {
  private static final long serialVersionUID = 1L;

  public CMISGenwyseAlreadyExistException(String message) {
    super(message);
  }
  public CMISGenwyseAlreadyExistException(String message, CmisContentAlreadyExistsException e) {
    super(message, e);
  }
}
