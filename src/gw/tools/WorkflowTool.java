package gw.tools;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import gw.database.DataBaseOperation;
import gw.tasks.GeoweaverWorkflowTask;
import gw.tasks.TaskManager;
import gw.utils.BaseTool;
import gw.utils.RandomString;
import gw.utils.STATUS;
import gw.web.GeoweaverController;

/**
 * 
 * @author JensenSun
 *
 */
public class WorkflowTool {
	
	public static Map<String, String> token2ws = new HashMap();
	
	private static Logger logger = Logger.getLogger(WorkflowTool.class);
	
	
	/**
	 * For Andrew
	 * @param history_id
	 * @return
	 */
	public static String stop(String history_id) {

        ProcessTool pt = new ProcessTool();

        StringBuffer pids = new StringBuffer();

        StringBuffer resp = new StringBuffer();

        StringBuffer sql = new StringBuffer("select input, output from history where id = '").append(history_id).append("';");

        try {

            ResultSet rs = DataBaseOperation.query(sql.toString());

            if(rs.next()) {
	
                pids.append("(");

                for (String id: rs.getString("output").split(";")) {

                    resp.append("\"").append(id).append("\"").append(", ");

                }

                pids.append(")");

            }

        } catch (Exception e) {

            e.printStackTrace();

            throw new RuntimeException(e.getLocalizedMessage());

        } finally {
	
            DataBaseOperation.closeConnection();

        }

        sql = new StringBuffer("select id from history where id in ").append(pids).append("and indicator='Running'").append(";");

        try {

            ResultSet rs = DataBaseOperation.query(sql.toString());

            if(rs.next()) {

                String pid = rs.getString("id");

                pt.stop(pid);

            }

        } catch (Exception e) {

            e.printStackTrace();

            throw new RuntimeException(e.getLocalizedMessage());

		} finally {

            DataBaseOperation.closeConnection();

        }

        sql = new StringBuffer("update history set end_time = '");

        String history_end_time = BaseTool.getCurrentMySQLDatetime();

        sql.append(history_end_time);

        sql.append("', indicator = 'Stopped' where id = '");

        sql.append(history_id).append("';");

        DataBaseOperation.execute(sql.toString());

        return null;
	}
	
	public static String list(String owner){
		
		StringBuffer json = new StringBuffer("[");
		
		try {
			
			ResultSet rs = DataBaseOperation.query("select * from abstract_model where length(identifier) < 30 ; ");
			
			int num = 0;
			
			while(rs.next()) {
				
				if(num!=0) {
					
					json.append(",");
					
				}
				
				json.append("{ \"id\": \"")
					.append(rs.getString("identifier"))
					.append("\", \"name\": \"")
					.append(rs.getString("name"))
					.append("\" }");
				
				num++;
				
			}
			
			json.append("]");
			
		} catch (SQLException e) {

			e.printStackTrace();
			
		}finally {

			DataBaseOperation.closeConnection();
			
		}
		
		return json.toString();
		
	}
	
	public static Workflow getById(String id) {
		
		Workflow w = null;

		StringBuffer sql = new StringBuffer("select * from abstract_model where identifier = '").append(id).append("';");
		
		StringBuffer resp = new StringBuffer();
		
		try {
			
			ResultSet rs = DataBaseOperation.query(sql.toString());
			
			if(rs.next()) {
				
				w = new Workflow();
				
				w.setName(rs.getString("name"));
				
				w.setId(rs.getString("identifier"));
				
				w.setNodes(rs.getString("process_connection"));
				
				w.setEdges(rs.getString("param_connection"));
				
			}
			
		} catch (Exception e) {
			
			e.printStackTrace();
			
			throw new RuntimeException(e.getLocalizedMessage());
			
		}finally {
			
			DataBaseOperation.closeConnection();
			
		}
		
		return w;
		
	}

	public static String detail(String id) {
		
		StringBuffer sql = new StringBuffer("select * from abstract_model where identifier = '").append(id).append("';");
		
		StringBuffer resp = new StringBuffer();
		
		try {
			
			ResultSet rs = DataBaseOperation.query(sql.toString());
			
			if(rs.next()) {
				
				resp.append("{ \"name\":\"");
				
				resp.append(rs.getString("name")).append("\", \"id\": \"");
				
				resp.append(id).append("\", \"nodes\":");
				
				resp.append(rs.getString("process_connection")).append(", \"edges\":");
				
				resp.append(rs.getString("param_connection")).append(" }");
				
			}
			
		} catch (Exception e) {
			
			e.printStackTrace();
			
			throw new RuntimeException(e.getLocalizedMessage());
			
		}finally {
			
			DataBaseOperation.closeConnection();
			
		}
		
		return resp.toString();
		
	}
	
	public static Map<String, List> getNodeConditionMap(JSONArray nodes, JSONArray edges) throws ParseException{
		
		//find the condition nodes of each process
		
		Map<String, List> node2condition = new HashMap();
		
		for(int i=0;i<nodes.size();i++) {
			
			String current_id = (String)((JSONObject)nodes.get(i)).get("id");
			
			List preids = new ArrayList();
			
			for(int j=0;j<edges.size();j++) {
				
				JSONObject eobj = (JSONObject)edges.get(j);
				
				String sourceid = (String)((JSONObject)eobj.get("source")).get("id");
				
				String targetid = (String)((JSONObject)eobj.get("target")).get("id");
				
				if(current_id.equals(targetid)) {
					
					preids.add(sourceid);
					
				}
				
				
			}

			node2condition.put(current_id, preids);
			
		}
		
		return node2condition;
		
	}
	
	/**
	 * Find a process whose status is not executed, while all of its condition nodes are satisfied. 
	 * @param nodemap
	 * @param flags
	 * @param nodes
	 * @return
	 */
	public static String[] findNextProcess(Map<String, List> nodemap, STATUS[] flags, JSONArray nodes) {
		
		String id = null;
		
		String num = null;
		
		for(int i=0;i<nodes.size();i++) {
			
			String currentid = (String)((JSONObject)nodes.get(i)).get("id");
			
			if(checkNodeStatus(currentid, flags, nodes)!=STATUS.READY) {
				
				continue;
				
			}
			
			List prenodes = nodemap.get(currentid);
			
			boolean satisfied = true;
			
			//check if all the prenodes are satisfied
			
			for(int j=0;j<prenodes.size();j++) {
				
				String prenodeid = (String)prenodes.get(j);
				
				//if any of the pre- nodes is not satisfied, this node is passed. 
				
				if(checkNodeStatus(prenodeid, flags, nodes)!=STATUS.DONE
						&&checkNodeStatus(prenodeid, flags, nodes)!=STATUS.FAILED) {
					
					satisfied = false;
					
					break;
					
				}
				
			}
			
			if(satisfied) {
				
				id = currentid;
				
				num = String.valueOf(i);
				
				break;
				
			}
			
		}
		
		String[] ret = new String[] {id, num};
		
		return ret;
		
	}
	
	/**
	 * Update the status of a node
	 * @param id
	 * @param flags
	 * @param nodes
	 * @param status
	 */
	public static void updateNodeStatus(String id, STATUS[] flags, JSONArray nodes, STATUS status) {
		
		for(int j=0;j<nodes.size();j++) {
			
			String prenodeid = (String)((JSONObject)nodes.get(j)).get("id");
			
			if(prenodeid.equals(id)) {
				
				flags[j] = status;
				
				break;
				
			}
			
		}
		
	}
	
	/**
	 * Check the status of a node
	 * @param id
	 * @param flags
	 * @param nodes
	 * @return
	 */
	private static STATUS checkNodeStatus(String id, STATUS[] flags, JSONArray nodes) {
		
		STATUS status = null;
		
		for(int j=0;j<nodes.size();j++) {
			
			String nodeid = (String)((JSONObject)nodes.get(j)).get("id");
			
			if(nodeid.equals(id)) {
				
				status = flags[j];
				
				break;
				
			}
			
		}
		
		return status;
		
	}

	/**
	 * Execute a workflow
	 * @param id
	 * @param mode
	 * @param hosts
	 * @param pswd
	 * @param token
	 * @return
	 */
	public static String execute(String id, String mode, String[] hosts, String[] pswds, String token) {
		
		//use multiple threads to execute the processes
		
		String resp = null;
		
		try {
			
			GeoweaverWorkflowTask task = new GeoweaverWorkflowTask("GW-Workflow-Run-" + token);
			
			task.initialize(id, mode, hosts, pswds, token);
			
			TaskManager.addANewTask(task);

			resp = "{\"history_id\": \""+task.getHistory_id()+
					
					"\", \"token\": \""+token+
					
					"\", \"ret\": \"success\"}";
			
			//register the input/output into the database
	        
		} catch (Exception e) {
			
			e.printStackTrace();
			
			throw new RuntimeException(e.getLocalizedMessage());
			
		} 
        		
		return resp;
		
	}
	
	/**
	 * Update workflow nodes and edges
	 * @param wid
	 * @param nodes
	 * @param edges
	 */
	public static void update(String wid, String nodes, String edges) {
		
		StringBuffer sql = new StringBuffer("update abstract_model set process_connection = ?, param_connection = ? where identifier = '");
		
		sql.append(wid).append("'; ");
		
		DataBaseOperation.preexecute(sql.toString(), new String[] {nodes, edges});
		
		
	}

	
	public static String add(String name, String nodes, String edges) {
		
		String newid = new RandomString(20).nextString();
		
		logger.info("name: " + name + "\nnodes: " + nodes + "\nedges: " + edges);
		
		StringBuffer sql = new StringBuffer("insert into abstract_model (identifier, name, namespace, process_connection, param_connection) values ('");
		
		sql.append(newid).append("', '");
		
		sql.append(name).append("', 'http://geoweaver.csiss.gmu.edu/workflow/");
		
		sql.append(name).append("', ?, ? )");
		
		DataBaseOperation.preexecute(sql.toString(), new String[] {nodes, edges});
		
		return newid;
		
	}
	
	public static String del(String workflowid) {
		
		StringBuffer sql = new StringBuffer("delete from abstract_model where identifier = '").append(workflowid).append("';");
		
		DataBaseOperation.execute(sql.toString());
		
		return "done";
		
	}
	
	/**
	 * Get all active processes
	 * @return
	 */
	public static String all_active_process() {
		
		StringBuffer resp = new StringBuffer() ;
		
		StringBuffer sql = new StringBuffer("select * from history, abstract_model where history.process = abstract_model.identifier and indicator = 'Running' ORDER BY begin_time DESC;");
		
		ResultSet rs = DataBaseOperation.query(sql.toString());
		
		try {
			
			resp.append("[");
			
			int num = 0;
			
			while(rs.next()) {
				
				if(num!=0) {
					
					resp.append(", ");
					
				}
				
				resp.append("{ \"id\": \"").append(rs.getString("id")).append("\", ");
				
				resp.append("\"begin_time\": \"").append(rs.getString("begin_time")).append("\", ");
				
				resp.append("\"end_time\": \"").append(rs.getString("end_time")).append("\", ");
				
				resp.append("\"status\": \"").append(ProcessTool.escape(rs.getString("indicator"))).append("\", ");
				
				resp.append("\"output\": \"").append(rs.getString("output")).append("\"}");
				
				num++;
				
			}
			
			resp.append("]");
			
			if(num==0)
				
				resp = new StringBuffer();
			
		} catch (SQLException e) {
			
			e.printStackTrace();
			
		}finally {
			
			DataBaseOperation.closeConnection();
			
		}
		
		return resp.toString();
		
		
	}

	/**
	 * show the history of every execution of the workflow
	 * @param string
	 * @return
	 */
	public static String all_history(String workflow_id) {
		
		HistoryTool tool = new HistoryTool();
		
		return tool.workflow_all_history(workflow_id);
		
	}
	
	/**
	 * List to JSON
	 * @param list
	 * @return
	 */
	public static String list2JSON(String list) {
		
		StringBuffer json = new StringBuffer("[");
		
		String[] ps = list.split(";");
		
		for(int i=0;i<ps.length;i++) {
			
			if(i!=0) {
				
				json.append(",");
				
			}
			
			json.append("\"").append(ps[i]).append("\"");			
		}
		
		json.append("]");
		
		return json.toString();
		
	}
	
	public static String recent(int limit) {
		
		StringBuffer resp = new StringBuffer();
		
		StringBuffer sql = new StringBuffer("select * from history, abstract_model where abstract_model.identifier = history.process ORDER BY begin_time DESC limit ").append(limit).append(";");

		ResultSet rs = DataBaseOperation.query(sql.toString());
		
		try {
			
			resp.append("[");
			
			int num = 0;
			
			while(rs.next()) {
				
				if(num!=0) {
					
					resp.append(", ");
					
				}
				
				resp.append("{ \"id\": \"").append(rs.getString("id")).append("\", "); //history id
				
				resp.append("\"name\": \"").append(rs.getString("name")).append("\", ");
				
				resp.append("\"end_time\": \"").append(rs.getString("end_time")).append("\", ");
				
				resp.append("\"begin_time\": \"").append(rs.getString("begin_time")).append("\"}");
				
				num++;
				
			}
			
			resp.append("]");
			
			if(num==0)
				
				resp = new StringBuffer();
			
		} catch (SQLException e) {
			
			e.printStackTrace();
			
		}finally {
			
			DataBaseOperation.closeConnection();
			
		}
		
		return resp.toString();
		
	}

	public static String one_history(String hid) {

		StringBuffer resp = new StringBuffer();
		
		StringBuffer sql = new StringBuffer("select * from history where id = '").append(hid).append("';");
		
		try {
			
			ResultSet rs = DataBaseOperation.query(sql.toString());
			
			if(rs.next()) {
				
				resp.append("{ \"hid\": \"").append(rs.getString("id")).append("\", ");
				
				resp.append("\"process\": \"").append(rs.getString("process")).append("\", ");
				
				resp.append("\"begin_time\":\"").append(rs.getString("begin_time")).append("\", ");
				
				resp.append("\"end_time\":\"").append(rs.getString("end_time")).append("\", ");
				
				String processes = rs.getString("input");
				
				String histories = rs.getString("output");
				
				resp.append("\"input\":").append(list2JSON(processes)).append(", ");
				
				resp.append("\"output\":").append(list2JSON(histories)).append(" }");
				
			}
			
		} catch (SQLException e) {
		
			e.printStackTrace();
			
		}finally {
			
			DataBaseOperation.closeConnection();
			
		}
		
		return resp.toString();
		
	}
	
	public static void main(String[] args) throws ParseException {
		
		String jsonarray = "[{\"name\": \"1\"}, {\"name\": \"2\"}]";
		
		JSONParser parser = new JSONParser();
		
		JSONArray obj = (JSONArray)parser.parse(jsonarray);
		
		System.out.println("parsed json objects: " + obj.size());
		
		
	}

}
