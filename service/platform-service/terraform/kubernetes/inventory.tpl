[all:vars]
ansible_connection=local
kubeconfig=${kubeconfig}
cluster_name=${cluster_name}
region=${region}

[kubernetes_cluster]
localhost

[system_nodes]
%{ for node in system_nodes ~}
${node.label}
%{ endfor ~}

[application_nodes]
%{ for node in application_nodes ~}
${node.label}
%{ endfor ~}