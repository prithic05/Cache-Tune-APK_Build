--> First need initialize the docker  to run the wrapper
#####cmd -> docker run --privileged --rm -it `
-p 10020:10020 `
-p 20020:20020 `
-p 30020:30020 `
-v ./rootfs/data:/app/rootfs/data `
--entrypoint ./wrapper `
ghcr.io/worldobservationlog/wrapper:local `
-L "Onlyeducation7969@gmail.com:Onlyedu79@" `
-H 0.0.0.0


--> On the apple-music-downloader 
#####cmd -> go run main.go <paste song url> 