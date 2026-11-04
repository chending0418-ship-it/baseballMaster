# Exact product aliases: keep published .html URLs working and avoid API paths.
location = /scorekeeper {
    try_files /scorekeeper.html =404;
}
location = /scorekeeper/ {
    return 308 /scorekeeper$is_args$args;
}
location = /scorekeeper.html {
    return 301 /scorekeeper$is_args$args;
}
location = /coach-insights {
    try_files /coach-insights.html =404;
}
location = /coach-insights/ {
    return 308 /coach-insights$is_args$args;
}
location = /coach-insights.html {
    return 301 /coach-insights$is_args$args;
}
