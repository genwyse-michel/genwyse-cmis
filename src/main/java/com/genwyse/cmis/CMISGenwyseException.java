package com.genwyse.cmis;

public class CMISGenwyseException extends Exception {

  private static final long serialVersionUID = 1L;

  public CMISGenwyseException(String message, Exception e) {
    super(message, e);
  }
  public CMISGenwyseException(String message) {
    super(message);
  }
  
}
