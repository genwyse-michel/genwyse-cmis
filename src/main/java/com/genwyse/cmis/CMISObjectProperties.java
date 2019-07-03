package com.genwyse.cmis;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.client.api.ObjectType;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.log4j.Logger;

public class CMISObjectProperties extends HashMap<String, Object> 
{
  public static final Logger logger = Logger.getLogger(CMISObjectProperties.class);
  private static final long serialVersionUID = 1L;
  public static final SimpleDateFormat europeanDateFormat = new SimpleDateFormat("dd/MM/yyyy");

  private Session gedSession;
  private SimpleDateFormat dateFormat = europeanDateFormat;
  String dataLocation = "";
  
  public void setDateFormat(SimpleDateFormat dateFormat) {
    this.dateFormat = dateFormat;
  }
  
  public SimpleDateFormat getDateFormat() {
    return dateFormat;
  }
  
  public CMISObjectProperties(Session gedSession) {
    super();
    this.gedSession = gedSession;
  }
  
  // Construction dans le cas des données Etudiant lues dans un fichier (index sous forme de chaines)
  public CMISObjectProperties(CMISObjectStringProperties stringProperties, Session gedSession, String objectTypeName, String dataLocation) {
    super();
    this.gedSession = gedSession;
    this.dataLocation = dataLocation;
    convertProperties (objectTypeName, stringProperties);
  }
  
  // Construction dans le cas d'un objet quelconque et d'un dictionnaire d'index quelconque
  public CMISObjectProperties(String objectTypeName, Map<String,Object> properties, Session gedSession, String dataLocation) {
    super();
    this.gedSession = gedSession;
    this.dataLocation = dataLocation;
    convertProperties (objectTypeName, properties);
  }
  
  public void addSecondaryType(String secondaryType) {
    @SuppressWarnings("unchecked")
    List<String> secondaryTypes = (List<String>) get(PropertyIds.SECONDARY_OBJECT_TYPE_IDS);
    if (secondaryTypes==null) {
      secondaryTypes = new ArrayList<String>();
    }
    else {
      for (String type: secondaryTypes) {
        if (type.equals(secondaryType)) {
          // existe déjà
          return;
        }
      }
    }
    secondaryTypes.add(secondaryType);
    put(PropertyIds.SECONDARY_OBJECT_TYPE_IDS, secondaryTypes);
  }
  

  /*
   * Convertit les valeurs dans la classe d'objet nécessaire selon la définition des propriétés en GED
   */
  public void convertProperties (String objectTypeName, Map<String,?> properties) {
    // Conversion des valeurs selon leur définition 
    ObjectType objectType = this.gedSession.getTypeDefinition(objectTypeName);
    Map<String, PropertyDefinition<?>> propDefs = objectType.getPropertyDefinitions();
    
    for (Iterator<String> i = properties.keySet().iterator(); i.hasNext();) {
      String propName = i.next();
      Object propValue = properties.get(propName);
      
      Object propObject = convertPropValue(propDefs, propName, propValue, dataLocation);
      put(propName, propObject);
    }
  }
  /**
   * Return appropriate Object for a property
   * @param anObjHnd: handle string representation of the object
   * @param aPropName: property name
   * @param aPropValue: value to set
   * @return appropriate Object 
   */
  public Object convertPropValue( Map<String, PropertyDefinition<?>> propDefs, String propName, Object propValue, String location ) {
    Object propValueObj = null;
    if (propValue!=null) { // Si la valeur est nulle pas de conversion
      PropertyDefinition<?> propDef = propDefs.get(propName);
      if (propDef!=null) {
        PropertyType propType = propDef.getPropertyType();
        try {
          switch (propType) {
          case STRING:
            {
              String strValue = propValue.toString();
              if(propDef.getCardinality()==Cardinality.MULTI) {
                // Multivalué (on ne le traite que sur les chaines)
                propValueObj = strValue.split( "\\s+" );
              }
              else if (!propDef.isRequired() && "".equals(strValue)) {
                // Cas particulier : il y a une possibilité que la chaine ait une longueur minimale 
                // dans la GED, cependant cette information n'est pas accessible via CMIS (ils ont
                // pensé à gérer la longueur maximale, mais pas la longueur minimale...).
                // 
                // Si la valeur est une chaine vide et que la propriété n'est pas requise, le fait
                // de fournir une chaine vide  (au lieu d'une absence de valeur) peut alors générer une erreur.
                // => dans ce cas, on remplace la chaine vide par null.
              
                propValueObj = null;
              }
              else {
                propValueObj = strValue;
              }
            }
            break;
          case INTEGER:
            if (propValue instanceof String) {
              if ("".equals(propValue)) {
                propValueObj = null;
              }
              else {
                propValueObj = new Integer((String) propValue);
              }
            }
            else if (propValue instanceof Integer) { 
              propValueObj = propValue;
            }
            else if (propValue instanceof Long) { 
              propValueObj = ((Long)propValue).intValue();
            }
            else {
              logger.warn("Conversion inconnue :"+propValue.getClass().getName()+" => INTEGER");
            }
            break;
          case BOOLEAN:
            if (propValue instanceof String) {
              propValueObj = ((String) propValue).toLowerCase();
              propValueObj = ("1".equals(propValue)) ||("true".equals(propValue));
            }
            else if (propValue instanceof Boolean) { 
              propValueObj = propValue;
            }
            else if (propValue instanceof Integer) {
              propValueObj = ((Integer) propValue==1);
            }
            else {
              logger.warn("Conversion inconnue :"+propValue.getClass().getName()+" => BOOLEAN");
            }
            break;
          case DECIMAL:
            if (propValue instanceof String) {
              propValueObj = new Double((String)propValue);
            }
            else if (propValue instanceof Boolean) { 
              propValueObj = ((Boolean)propValue) ? 1.0 : 0.0;
            }
            else if (propValue instanceof Integer) {
              propValueObj = new Double((Integer) propValue);
            }
            else {
              logger.warn("Conversion inconnue :"+propValue.getClass().getName()+" => DECIMAL");
            }
            break;
          case DATETIME:
            // Le type date est retourné comme GregorianCalendar ???
            if (propValue instanceof String) {
              propValueObj = convertImportedDate((String)propValue , "");
            }
            else if (propValue instanceof Date) {
              propValueObj = propValue;
            }
            else {
              logger.warn("Conversion inconnue :"+propValue.getClass().getName()+" => DATETIME");
            }
            break;
          default:
            // On ne fait pas de conversion on verra bien
            propValueObj = propValue;
            break;
          }
        } catch ( ParseException e ) {
          logger.error("["+location+"] formatPropValue(): "+e.getMessage() );
          return null;
        } catch ( NumberFormatException e ) {
          logger.error( "["+location+"] formatPropValue(): " + propValue + " is in a wrong number format.\n" );
          return null;
        } catch ( IllegalArgumentException e ) {
          logger.error( "["+location+"] formatPropValue(): "+e.getMessage() );
          return null;
        }
        
      }
      else {
        // Nom de propriété inconnu ??
        logger.warn("\"[\"+location+\"] Propriété inconnue :"+propName);
      }
    }
    return propValueObj;
  }

  protected Object convertImportedDate(String date, String location) throws ParseException {
    return dateFormat.parseObject(date);
  }

}