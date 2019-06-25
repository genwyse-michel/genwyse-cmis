package com.genwyse.cmis;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.client.api.FileableCmisObject;
import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.client.api.ItemIterable;
import org.apache.chemistry.opencmis.client.api.ObjectId;
import org.apache.chemistry.opencmis.client.api.QueryResult;
import org.apache.chemistry.opencmis.client.api.QueryStatement;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.util.ContentStreamUtils;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.exceptions.CmisConnectionException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisContentAlreadyExistsException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisVersioningException;
import org.apache.chemistry.opencmis.commons.impl.MimeTypes;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.apache.log4j.Logger;

public class CMISGenwyse {
  private static final Logger logger = Logger.getLogger(CMISGenwyse.class);
  
  public static final String classProp = "class";
  
  /*
   * Comportement si le document existe déjà
   */
  public enum Versionning {
    None,   // Génère une erreur
    Minor,  // Import d'une version mineure
    Major,  // Import d'une version majeure
  }

  public static void moveObject (FileableCmisObject item, Folder fromFolder, Folder toFolder) 
  {
    if (logger.isDebugEnabled()) logger.debug("Move "+item.getName()+" from "+fromFolder.getName()+" to "+toFolder.getName());
    item.move(fromFolder, toFolder);
  }

  public static FileableCmisObject lookupChildByProps (Session gedSession, Folder parent, Map<String,Object> props) {
    return lookupChildByProps (gedSession, parent, props, false);
  }
  
  public static FileableCmisObject lookupChildByProps (Session gedSession, Folder parent, Map<String,Object> props, boolean use_regex) {
    FileableCmisObject child = null;
    String [] prop_names = props.keySet().toArray(new String[0]);
    for (CmisObject obj : parent.getChildren()) {
      boolean good_obj = true;
      for (String prop: prop_names) {
        Object wanted_prop = props.get(prop);
        // Cas particulier: classe
        if (classProp.equals(prop)) {
          if (!obj.getType().getId().equals((String) wanted_prop)) {
            good_obj = false;
            break;
          }
        }
        else {
          if (use_regex && wanted_prop instanceof String) {
            // Vérification avec regexp
            String obj_prop = obj.getProperty(prop).getValueAsString();
            String wanted_string = (String) wanted_prop;
            if (!obj_prop.matches(wanted_string)) {
              good_obj = false;
              break;
            }
          }
          else {
            Object obj_prop = obj.getProperty(prop).getValue();
            if (!wanted_prop.equals(obj_prop)) {
              // Les valeurs d'objets sont différentes mais si la valeur attendue est une chaine
              // on va tenter de convertir en String pour les attributs d'un autre type
              if (wanted_prop instanceof String) {
                String str_obj_prop = obj.getProperty(prop).getValueAsString();
                if (!wanted_prop.equals(str_obj_prop)) {
                  good_obj = false;
                  break;
                }
              }
            }
          }
        }
      }
      if (good_obj && obj instanceof FileableCmisObject) {
        child = (FileableCmisObject) obj;
        break;
      }
    }
    return child;
  }
  
  /*
   * Recherche un objet (document ou dossier, selon le type indiqué) dans une arborescence par ses propriétés.
   * Le premier objet répondant aux critères est retourné.
   */
  static public CmisObject lookupDescendantByProps (Session gedSession, Folder rootFolder, String itemTypeId, Map<String,Object> props) {
    List<CmisObject> objects = lookupDescendantsByProps(gedSession, rootFolder, itemTypeId, props, 1);
    if (objects.size()>0) {
      return objects.get(0);
    }
    else {
      return null;
    }
  }
  
  /*
   * Recherche des objets (documents ou dossiers , selon le type indiqué) dans une arborescence par leurs propriétés.
   */
  static public List<CmisObject> lookupDescendantsByProps (Session gedSession, Folder rootFolder, String itemTypeId, Map<String,Object> props, int maxCount) {
    // Exemple de requête :
    //   SELECT cmis:objectId
    //   FROM <type>
    //   WHERE
    //     IN_TREE(...)
    //     AND <prop1> = ... AND <prop2> = ... 
    //   ORDER BY cmid:name
    // 
    List<CmisObject> objects = new LinkedList<CmisObject>();
    
    List<Object> queryParameters = new LinkedList<Object> ();
    StringBuffer itemQuery = new StringBuffer();
    itemQuery.append(
      "SELECT cmis:objectId FROM " + itemTypeId + " WHERE " +
      "IN_TREE(?) "
    );
    queryParameters.add(rootFolder.getId());
    
    for (String key: props.keySet()) {
      itemQuery.append("AND ");
      itemQuery.append(key);
      itemQuery.append(" = ? ");
      queryParameters.add(props.get(key));
    }
    itemQuery.append(" ORDER BY cmis:name ");
    
    // Crée la requête
    QueryStatement qs = gedSession.createQueryStatement(itemQuery.toString());
    int iQueryParameter = 1;
    for (Object queryParameter : queryParameters) {
      if (queryParameter instanceof String) {
        qs.setString(iQueryParameter++, (String) queryParameter);
      }
      else {
        logger.error("Cas de type de propriété non implémenté: "+queryParameter.getClass().getName());
        qs.setString(iQueryParameter++, queryParameter.toString());
      }
    }
    
    // La requête compilée
    String statement = qs.toQueryString();
    logger.debug("Recherche d'objet dans l'arborescence: "+statement);
    ItemIterable<QueryResult> results = gedSession.query(statement, false);
    int itemCount = 0;
    for (QueryResult result : results) {
      itemCount++;
      if (itemCount>maxCount) {
        break;
      }
      String itemId = (String) result.getProperties().get(0).getFirstValue();
      CmisObject cmisObject = gedSession.getObject(itemId);
      objects.add(cmisObject);
    }
    logger.debug("Nombre d'item trouvés: "+itemCount);

    return objects;
  }
  
  public static Folder createCollection (Session gedSession, Folder parent, String collection_class, Map<String,Object> props) {
    if (collection_class==null||"".equals(collection_class)) {
      collection_class = "cmis:folder";
    }
    
    props.put(PropertyIds.OBJECT_TYPE_ID, collection_class);
    if (logger.isDebugEnabled()) logger.debug("createCollection "+props.get(PropertyIds.NAME)+" in "+parent.getName());
    Folder coll = (Folder) gedSession.createFolder(props, parent);
    return coll;
  }
  
  private static ContentStream createFileContentStream (File contentFile) throws FileNotFoundException {
    // Attention: Problème avec ContentStreamUtils.createFileContentStream
    // Les fichiers csv passent à 267 cars ils passent, à 268 cars il ne passent pas
    // et génèrent une exception bizarre de flux déjà fermé lors de l'envoi au serveur. 
    // Un fichier pdf plus gros passe sans problème.
    //
    // => pb avec ContentStreamUtils.createFileContentStream ???
    //ContentStream contentStream = ContentStreamUtils.createFileContentStream(contentFile);
    String fileName = contentFile.getName();
    String fileExt = "";
    int i = fileName.lastIndexOf('.');
    if (i>0) {
      fileExt = fileName.substring(i+1);
    }
    String mimeType = MimeTypes.getMIMEType(fileExt);  
    InputStream stream = new FileInputStream(contentFile);
    ContentStream contentStream = new ContentStreamImpl(fileName, BigInteger.valueOf(contentFile.length()), mimeType, stream);
    return contentStream;
  }
  
  public static Document createDocument (Session gedSession, Folder parent, String document_class, Map<String,Object> props, File content_file, String gedFilename, Versionning versionning) throws Exception {
    if (document_class==null||"".equals(document_class)) {
      document_class = "cmis:document";
    }
    props.put(PropertyIds.OBJECT_TYPE_ID, document_class);
    props.put(PropertyIds.NAME, gedFilename);

    // Contenu
    ContentStream contentStream = createFileContentStream(content_file);
    
    // Crée le document
    try {
      if (logger.isTraceEnabled()) {
        logger.trace("Import du document "+gedFilename);
        ArrayList<String> propNames = new ArrayList<String>(props.keySet());
        Collections.sort(propNames);
        for (String propName : propNames) {
          logger.trace("  "+propName+": "+props.get(propName));
        }
      }
      Document newDoc = parent.createDocument(props, contentStream, VersioningState.MINOR);
      return newDoc;
    } catch (CmisContentAlreadyExistsException e) {
      // Le document existe
      boolean createMajor = false;
      
      switch (versionning) {
      case None:
        throw new CMISGenwyseAlreadyExistException("Le document existe déjà: "+gedFilename);
      case Minor:
        createMajor = false;
        break;
      case Major:
        createMajor = true;
        break;
      }
      // Le document existe => mise à jour du contenu avec historique
      Map<String,Object> propsToSearch = new HashMap<String,Object> ();
      propsToSearch.put(PropertyIds.NAME, gedFilename);
      FileableCmisObject item = CMISGenwyse.lookupChildByProps(gedSession, parent, propsToSearch);
      if (item instanceof Document) {
        Document oldDoc = (Document) item;
        logger.info("Le document existe : mise à jour de: "+oldDoc.getId());
        Document pwc = null;
        try {
          ObjectId pwcId = oldDoc.checkOut();
          pwc = (Document) gedSession.getObject(pwcId);
          contentStream = ContentStreamUtils.createFileContentStream(content_file);
          pwc.checkIn(createMajor, props, contentStream, "");
          pwc = null;
          return oldDoc;
        } catch (CmisVersioningException e1) {
          logger.error("Erreur CMIS: " + e1.getMessage());
          throw e1;
        } catch (Exception e1) {
        	logger.error("Exception non prévue: "+e1.getMessage());
          throw e1;
        } finally {
        	if (pwc!=null) {
        		pwc.cancelCheckOut(); // Pour ne pas laisser de document verrouillé
        	}
        }
      } 
    } catch (CmisConnectionException e) {
      logger.error("Erreur à la création du document "+e.getMessage());
      throw e;
    } finally {
      // Ferme le flux
      try {
    	  InputStream s = contentStream.getStream();
    	  if (s!=null) {
    		  s.close();
    	  }
      } catch (IOException e) {
        logger.error(e.getMessage());
      }
    }
    return null;
  }
  
  public static String buildItemDetailsURL(String gedRoot, CmisObject item) {
    String itemPage = "";
    if (item instanceof Document) {
      itemPage = "document-details";
    }
    else if (item instanceof Folder) {
      itemPage = "folder-details";
    }
    else {
      return "";
    }
    String itemId = item.getId();
    
    // Apparemment l'item Id a à la fin un n° de version qui ne passe pas sur l'URL
    Pattern p = Pattern.compile("(.*);[0-9]+\\.[0-9]+$");
    Matcher m = p.matcher(itemId);
    if (m.matches()) {
      // L'id est de la forme ...;nn.mm , on enlève la fin
      itemId = m.group(1);
    }
    return gedRoot + "/page/" + itemPage + "?nodeRef=workspace://SpacesStore/" + itemId;
    
  }
}
